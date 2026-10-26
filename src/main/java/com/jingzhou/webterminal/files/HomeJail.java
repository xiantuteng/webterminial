package com.jingzhou.webterminal.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 解析文件面板路径。空路径、.、~、~/ 和普通相对路径都相对主目录，
 * 相对路径里的 .. 不能离开主目录。以 / 开头的绝对路径按文件系统解析，
 * 不拼到主目录下面；只有 allowOutside 时才允许落到主目录之外。
 * 符号链接先 toRealPath，再按同一越界规则判断。
 */
public final class HomeJail {

    private final Path home;
    private final boolean allowOutside;

    public HomeJail(Path home) {
        this(home, false);
    }

    public HomeJail(Path home, boolean allowOutside) {
        this.allowOutside = allowOutside;
        try {
            Path real = home.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("主目录不可用");
            }
            this.home = real;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("主目录不可用");
        }
    }

    public Path home() {
        return home;
    }

    public Path resolveExisting(String raw) {
        Path path = resolve(raw, true);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("路径不存在");
        }
        return path;
    }

    public Path resolveForCreate(String raw) {
        return resolve(raw, false);
    }

    private Path resolve(String raw, boolean mustExist) {
        String text = toHomeRelative(raw);
        boolean absolute = text.startsWith("/");
        while (text.endsWith("/") && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        if (".".equals(text)) {
            text = "";
            absolute = false;
        }
        Path target;
        try {
            if (text.isEmpty()) {
                target = home;
            } else if (absolute) {
                target = Paths.get(text).normalize();
            } else {
                target = home.resolve(text).normalize();
            }
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("路径无效");
        }
        if (!absolute && !target.startsWith(home)) {
            throw new IllegalArgumentException("路径无效");
        }
        if (absolute && !target.startsWith(home) && !allowOutside) {
            throw new IllegalArgumentException("路径无效");
        }
        try {
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                if (!real.startsWith(home) && !allowOutside) {
                    throw new IllegalArgumentException("路径无效");
                }
                return real;
            }
            if (mustExist) {
                throw new IllegalArgumentException("路径不存在");
            }
            Path parent = target.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("路径无效");
            }
            if (!Files.isDirectory(parent)) {
                throw new IllegalArgumentException("路径不存在");
            }
            Path parentReal = parent.toRealPath();
            if (!parentReal.startsWith(home) && !allowOutside) {
                throw new IllegalArgumentException("路径无效");
            }
            Path name = target.getFileName();
            if (name == null || name.toString().isEmpty() || ".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IllegalArgumentException("路径无效");
            }
            return parentReal.resolve(name);
        } catch (IllegalArgumentException ex) {
            if (ex instanceof InvalidPathException) {
                throw new IllegalArgumentException("路径无效");
            }
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("路径无效");
        }
    }

    /**
     * 把 ~ 和 ~/ 收成主目录下的相对路径。以 / 开头的绝对路径保持原样，
     * 前导 / 不能剥掉，否则 /Users/john/foo 会被当成主目录里的 Users/john/foo。
     */
    private String toHomeRelative(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("路径无效");
        }
        text = text.replace('\\', '/');
        if ("~".equals(text) || "~/".equals(text)) {
            return "";
        }
        if (text.startsWith("~/")) {
            text = text.substring(2);
            while (text.startsWith("/")) {
                text = text.substring(1);
            }
            return text;
        }
        return text;
    }

    public static Path pathOf(String home) {
        return Paths.get(home);
    }
}
