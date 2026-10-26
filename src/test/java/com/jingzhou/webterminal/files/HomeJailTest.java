package com.jingzhou.webterminal.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeJailTest {

    @TempDir
    Path home;

    @Test
    void staysInsideHome() throws Exception {
        Files.write(home.resolve("note.txt"), "hi".getBytes(StandardCharsets.UTF_8));
        HomeJail jail = new HomeJail(home);
        assertEquals(home.toRealPath(), jail.resolveExisting(null));
        assertEquals("hi", new String(Files.readAllBytes(jail.resolveExisting("note.txt")), StandardCharsets.UTF_8));
        Path created = jail.resolveForCreate("item.txt");
        assertTrue(created.normalize().startsWith(home.toRealPath()));
    }

    @Test
    void rejectsEscape() throws Exception {
        Path outside = Files.createTempDirectory("wt-outside");
        try {
            Files.write(outside.resolve("secret.txt"), "no".getBytes(StandardCharsets.UTF_8));
            HomeJail jail = new HomeJail(home);
            assertThrows(IllegalArgumentException.class, () ->
                    jail.resolveExisting("../" + outside.getFileName() + "/secret.txt"));
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void resolvesTildeAndAbsolutePathsWithinHome() throws Exception {
        Path sub = Files.createDirectories(home.resolve("subdir"));
        Files.write(sub.resolve("note.txt"), "ok".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(home.resolve("etc"));
        HomeJail jail = new HomeJail(home);
        Path realHome = jail.home();
        String inside = realHome.resolve("subdir").toString();

        assertEquals(realHome, jail.resolveExisting("~"));
        assertEquals(realHome, jail.resolveExisting("."));
        assertEquals(realHome, jail.resolveExisting(""));
        assertEquals(sub.toRealPath(), jail.resolveExisting("~/subdir"));
        assertEquals(sub.toRealPath(), jail.resolveExisting("~\\subdir"));
        assertEquals(sub.toRealPath(), jail.resolveExisting(inside));
        assertEquals("ok", new String(Files.readAllBytes(jail.resolveExisting(inside + "/note.txt")), StandardCharsets.UTF_8));
        assertEquals("路径无效", assertThrows(IllegalArgumentException.class,
                () -> jail.resolveExisting("~/..")).getMessage());

        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
                () -> jail.resolveExisting("/etc"));
        assertEquals("路径无效", outside.getMessage());
        if (realHome.getParent() != null) {
            IllegalArgumentException parent = assertThrows(IllegalArgumentException.class,
                    () -> jail.resolveExisting(realHome.getParent().toString()));
            assertEquals("路径无效", parent.getMessage());
        }
    }

    @Test
    void rejectsSymlinkLeavingHome() throws Exception {
        Path outside = Files.createTempDirectory("wt-link-out");
        Path secret = outside.resolve("secret.txt");
        Files.write(secret, "no".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(home.resolve("leak"), secret);
        try {
            HomeJail jail = new HomeJail(home);
            assertThrows(IllegalArgumentException.class, () -> jail.resolveExisting("leak"));
            assertThrows(IllegalArgumentException.class,
                    () -> jail.resolveExisting(jail.home().resolve("leak").toString()));
        } finally {
            Files.deleteIfExists(home.resolve("leak"));
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void absolutePathOutsideHomeRequiresPermission() throws Exception {
        Path outside = Files.createTempDirectory("wt-abs-outside");
        Path nested = Files.createDirectories(outside.resolve("nested"));
        Path note = nested.resolve("note.txt");
        Files.write(note, "yes".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("local.txt"), "in".getBytes(StandardCharsets.UTF_8));
        Path link = home.resolve("out-link");
        Files.createSymbolicLink(link, nested);
        try {
            Path outsideReal = outside.toRealPath();
            Path nestedReal = nested.toRealPath();
            assertTrue(!outsideReal.startsWith(home.toRealPath()));

            HomeJail closed = new HomeJail(home);
            HomeJail open = new HomeJail(home, true);
            String withSlash = outsideReal.toString() + "/";

            assertEquals("路径无效", assertThrows(IllegalArgumentException.class,
                    () -> closed.resolveExisting(withSlash)).getMessage());
            assertEquals("路径无效", assertThrows(IllegalArgumentException.class,
                    () -> closed.resolveForCreate(outsideReal.resolve("fresh.txt").toString())).getMessage());
            assertEquals("路径无效", assertThrows(IllegalArgumentException.class,
                    () -> closed.resolveExisting("out-link")).getMessage());

            assertEquals(outsideReal, open.resolveExisting(withSlash));
            assertEquals(nestedReal, open.resolveExisting(nestedReal.toString()));
            assertEquals("yes", new String(Files.readAllBytes(
                    open.resolveExisting(nestedReal.resolve("note.txt").toString())), StandardCharsets.UTF_8));
            assertEquals(outsideReal.resolve("fresh.txt"),
                    open.resolveForCreate(outsideReal.resolve("fresh.txt").toString()));
            assertEquals(nestedReal, open.resolveExisting("out-link"));
            assertEquals(open.home().resolve("local.txt"), open.resolveExisting("~/local.txt"));
            assertEquals(open.home(), open.resolveExisting("~"));
            assertEquals("路径无效", assertThrows(IllegalArgumentException.class,
                    () -> open.resolveExisting("..")).getMessage());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(note);
            Files.deleteIfExists(nested);
            Files.deleteIfExists(outside);
        }
    }
}
