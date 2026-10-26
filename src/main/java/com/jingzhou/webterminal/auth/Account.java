package com.jingzhou.webterminal.auth;

public final class Account {

    private final String username;
    private final String home;
    private final String shell;

    public Account(String username, String home, String shell) {
        this.username = username;
        this.home = home;
        this.shell = shell;
    }

    public String getUsername() {
        return username;
    }

    public String getHome() {
        return home;
    }

    public String getShell() {
        return shell;
    }
}
