package com.jingzhou.webterminal.files;

import com.jingzhou.webterminal.Api;
import com.jingzhou.webterminal.auth.SystemAccountService;
import com.jingzhou.webterminal.auth.WtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final int MAX_ENTRIES = 2000;

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "path", required = false) String path, HttpSession session)
            throws IOException {
        HomeJail jail = jail(session);
        Path dir = jail.resolveExisting(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是目录");
        }
        if (!Files.isReadable(dir)) {
            throw new IllegalArgumentException("没有可读权限");
        }
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(describe(child));
            }
        }
        Collections.sort(entries, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                boolean leftDir = Boolean.TRUE.equals(left.get("directory"));
                boolean rightDir = Boolean.TRUE.equals(right.get("directory"));
                if (leftDir != rightDir) {
                    return leftDir ? -1 : 1;
                }
                return String.valueOf(left.get("name")).compareToIgnoreCase(String.valueOf(right.get("name")));
            }
        });
        Map<String, Object> body = Api.ok();
        body.put("path", relative(jail, dir));
        body.put("truncated", truncated);
        body.put("entries", entries);
        return body;
    }

    @GetMapping("/download")
    public void download(@RequestParam("path") String path, HttpSession session, HttpServletResponse response)
            throws IOException {
        HomeJail jail = jail(session);
        Path file = jail.resolveExisting(decodeDownloadPath(path));
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("不能下载目录");
        }
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("没有可读权限");
        }
        String filename = file.getFileName() == null ? "download" : file.getFileName().toString();
        response.setStatus(200);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", contentDisposition(filename));
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam(value = "path", required = false) String path,
                                      @RequestParam("file") MultipartFile file,
                                      HttpSession session) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("请选择文件");
        }
        String filename = baseName(decodeUploadFilename(file.getOriginalFilename()));
        HomeJail jail = jail(session);
        Path dir = jail.resolveExisting(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("只能上传到目录");
        }
        String relativeDir = relative(jail, dir);
        String target = filename;
        if (!relativeDir.isEmpty()) {
            target = relativeDir.endsWith("/") ? relativeDir + filename : relativeDir + "/" + filename;
        }
        Path dest = jail.resolveForCreate(target);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> body = Api.ok();
        body.put("name", filename);
        body.put("path", relative(jail, dest.getParent() == null ? dir : dest.getParent()));
        return body;
    }

    private static Map<String, Object> describe(Path child) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String name = child.getFileName() == null ? "" : child.getFileName().toString();
        item.put("name", name);
        boolean directory = false;
        long size = 0L;
        long modified = 0L;
        try {
            directory = Files.isDirectory(child);
            if (!directory) {
                size = Files.size(child);
            }
            modified = Files.getLastModifiedTime(child).toMillis();
        } catch (IOException ex) {
            directory = false;
        }
        item.put("directory", directory);
        item.put("size", size);
        item.put("modified", modified);
        return item;
    }

    /**
     * 主目录内返回 ~/ 用的相对路径。主目录外返回真实绝对路径，
     * 避免前端把 /data 拼成 ~/data。
     */
    private static String relative(HomeJail jail, Path dir) {
        Path home = jail.home();
        Path normalized = dir.toAbsolutePath().normalize();
        if (normalized.equals(home)) {
            return "";
        }
        if (normalized.startsWith(home)) {
            return home.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    /**
     * Content-Disposition 的 filename 是真实文件名的 UTF-8 标准 Base64，与登录页相同。
     * 先整段解码，再取最后一段文件名；不要在解码前按 / 截断，否则 Base64 里的 / 会被拆开。
     */
    private static String decodeUploadFilename(String encoded) {
        return decodeUtf8Base64(encoded, "文件名无效");
    }

    private static String decodeDownloadPath(String encoded) {
        String text = decodeUtf8Base64(encoded, "路径无效");
        if (text.indexOf('\0') >= 0 || text.trim().isEmpty()) {
            throw new IllegalArgumentException("路径无效");
        }
        return text;
    }

    private static String decodeUtf8Base64(String encoded, String invalidMessage) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException(invalidMessage);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(invalidMessage);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return text;
    }

    private static String baseName(String original) {
        if (original == null || original.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名无效");
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name) || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("文件名无效");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("文件名过长");
        }
        return name;
    }

    private static String contentDisposition(String filename) throws IOException {
        String encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            char ch = filename.charAt(i);
            if (ch >= 0x20 && ch <= 0x7e && ch != '"' && ch != '\\') {
                ascii.append(ch);
            } else {
                ascii.append('_');
            }
        }
        if (ascii.length() == 0) {
            ascii.append("download");
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static HomeJail jail(HttpSession session) {
        if (session == null) {
            throw new IllegalStateException("未登录");
        }
        Object home = session.getAttribute(WtSession.HOME);
        if (!(home instanceof String) || ((String) home).isEmpty()) {
            throw new IllegalStateException("未登录");
        }
        try {
            return new HomeJail(HomeJail.pathOf((String) home), allowOutsideHome(session));
        } catch (IllegalArgumentException ex) {
            log.warn("主目录不可用");
            throw new IllegalStateException("主目录不可用");
        }
    }

    /**
     * 只有 root，或登录名与 JVM 进程用户相同，才允许绝对路径离开主目录。
     * JVM 以 root 运行时，普通账号登录仍然限制在自己的主目录。
     */
    private static boolean allowOutsideHome(HttpSession session) {
        Object user = session.getAttribute(WtSession.USER);
        if (!(user instanceof String)) {
            return false;
        }
        String username = (String) user;
        return "root".equals(username) || SystemAccountService.sameProcessUser(username);
    }
}
