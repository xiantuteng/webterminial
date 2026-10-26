package com.jingzhou.webterminal.files;

import com.jingzhou.webterminal.auth.WtSession;
import com.jingzhou.webterminal.config.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileControllerTest {

    private static final String PLAIN_NAME = "使用说明.txt";
    private static final String PLAIN_BASE64 = "5L2/55So6K+05piOLnR4dA==";
    private static final String FIXTURE_USER = "wt-fixture";

    @TempDir
    Path home;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void base64UploadAndDownloadRoundTrip() throws Exception {
        assertEquals(PLAIN_BASE64, base64(PLAIN_NAME));
        assertTrue(PLAIN_BASE64.contains("+"));
        assertTrue(PLAIN_BASE64.contains("/"));
        ContentDisposition header = ContentDisposition.parse(
                "form-data; name=\"file\"; filename=\"" + PLAIN_BASE64 + "\"");
        assertEquals(PLAIN_BASE64, header.getFilename());

        Files.createDirectories(home.resolve("notes"));
        byte[] payload = new byte[] {0x01, 0x02, 0x03, 'A'};
        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(PLAIN_BASE64, payload))
                        .param("path", "~/notes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(PLAIN_NAME))
                .andExpect(jsonPath("$.path").value("notes"));
        assertTrue(Arrays.equals(payload, Files.readAllBytes(home.resolve("notes").resolve(PLAIN_NAME))));

        String absoluteDir = home.resolve("notes").toRealPath().toString();
        byte[] asciiPayload = "abc".getBytes(StandardCharsets.UTF_8);
        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("report.txt"), asciiPayload))
                        .param("path", absoluteDir)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("report.txt"));

        mvc.perform(authed(get("/api/files").param("path", "~/notes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("notes"))
                .andExpect(jsonPath("$.entries[?(@.name == '使用说明.txt')]").exists())
                .andExpect(jsonPath("$.entries[?(@.name == '" + PLAIN_BASE64 + "')]").doesNotExist());

        String dispositionName = URLEncoder.encode(PLAIN_NAME, "UTF-8").replace("+", "%20");
        String downloadPath = base64("~/notes/" + PLAIN_NAME);
        mvc.perform(authed(get("/api/files/download?path={path}", downloadPath)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''" + dispositionName)))
                .andExpect(header().string("Content-Disposition", containsString("filename=\"____.txt\"")))
                .andExpect(header().string("Content-Disposition", not(containsString(downloadPath))))
                .andExpect(header().string("Content-Disposition", not(containsString(PLAIN_BASE64))))
                .andExpect(content().bytes(payload));

        String absoluteFile = absoluteDir + "/report.txt";
        mvc.perform(authed(get("/api/files/download").param("path", base64(absoluteFile))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename=\"report.txt\"")))
                .andExpect(content().bytes(asciiPayload));
    }

    @Test
    void rejectsInvalidOrEmptyBase64() throws Exception {
        Files.createDirectories(home.resolve("notes"));
        String bad = "@@@not-base64@@@";
        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(bad, "x".getBytes(StandardCharsets.UTF_8)))
                        .param("path", "~/notes")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"))
                .andExpect(content().string(not(containsString(bad))));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file("../etc/passwd", "x".getBytes(StandardCharsets.UTF_8)))
                        .param("path", "~/notes")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"))
                .andExpect(content().string(not(containsString("passwd"))));
        assertFalse(Files.exists(home.resolve("notes").resolve("passwd")));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "", "application/octet-stream", new byte[] {1}))
                        .param("path", "~/notes")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("   "), new byte[] {1}))
                        .param("path", "~/notes")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("/"), new byte[] {1}))
                        .param("path", "~/notes")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"));

        mvc.perform(authed(get("/api/files/download").param("path", bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("路径无效"))
                .andExpect(content().string(not(containsString(bad))));

        mvc.perform(authed(get("/api/files/download").param("path", base64("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("路径无效"));
    }

    @Test
    void decodedSeparatorsKeepBasenameOnly() throws Exception {
        byte[] payload = "kept".getBytes(StandardCharsets.UTF_8);
        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("nested/../../etc/passwd"), payload))
                        .param("path", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("passwd"));
        assertTrue(Arrays.equals(payload, Files.readAllBytes(home.resolve("passwd"))));
        assertFalse(Files.exists(home.resolve("nested")));
        assertFalse(Files.exists(home.resolve("etc")));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("..\\..\\secret.txt"), "s".getBytes(StandardCharsets.UTF_8)))
                        .param("path", "~")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("secret.txt"));
        assertTrue(Files.exists(home.resolve("secret.txt")));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("dir/sub/季度报告.txt"), "q".getBytes(StandardCharsets.UTF_8)))
                        .param("path", "~/")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("季度报告.txt"));
        assertTrue(Files.exists(home.resolve("季度报告.txt")));
        assertFalse(Files.exists(home.resolve("dir")));

        mvc.perform(authed(multipart("/api/files/upload")
                        .file(file(base64("foo/.."), new byte[] {1}))
                        .param("path", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名无效"));
    }

    @Test
    void consoleEncodesUploadFilenameAndDownloadPathOnly() throws Exception {
        URL resource = FileControllerTest.class.getResource("/static/console.html");
        assertNotNull(resource);
        String html = new String(Files.readAllBytes(Paths.get(resource.toURI())), StandardCharsets.UTF_8);
        assertTrue(html.contains("return btoa(unescape(encodeURIComponent(value)));"));
        assertTrue(html.contains("body.append(\"path\", current);"));
        assertTrue(html.contains("body.append(\"file\", file, utf8Base64(file.name));"));
        assertTrue(html.contains("encodeURIComponent(utf8Base64(join(current, entry.name)))"));
        assertTrue(html.contains("fetch(\"/wt/api/files?path=\" + encodeURIComponent(requested)"));
        assertTrue(html.contains("name.textContent = entry.name"));
        assertTrue(html.contains("link.download = entry.name"));
        assertTrue(html.contains("正在上传 \" + file.name"));
        assertFalse(html.contains("utf8Base64(requested)"));
        assertFalse(html.contains("utf8Base64(current)"));
        assertFalse(html.contains("utf8Base64(pathInput"));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder
                .sessionAttr(WtSession.HOME, home.toString())
                .sessionAttr(WtSession.USER, FIXTURE_USER);
    }

    private static MockMultipartFile file(String encodedName, byte[] body) {
        return new MockMultipartFile("file", encodedName, "application/octet-stream", body);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
