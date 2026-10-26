package com.jingzhou.webterminal.terminal;

import com.jingzhou.webterminal.auth.Account;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class ShellLauncher {

    public PtyProcess open(Account account, int columns, int rows) throws IOException {
        if (!new File(account.getHome()).isDirectory()) {
            throw new IOException("系统账号主目录不可用");
        }
        String self = System.getProperty("user.name", "");
        if (self.equals(account.getUsername())) {
            return direct(account, columns, rows);
        }
        String su = new File("/usr/bin/su").isFile() ? "/usr/bin/su" : "/bin/su";
        if (!new File(su).isFile()) {
            throw new IOException("无法切换到该系统账号");
        }
        return new PtyProcessBuilder()
                .setCommand(new String[]{su, "-l", account.getUsername()})
                .setEnvironment(environment(account))
                .setDirectory(account.getHome())
                .setRedirectErrorStream(true)
                .setInitialColumns(Integer.valueOf(columns))
                .setInitialRows(Integer.valueOf(rows))
                .start();
    }

    public boolean needsSwitchUser(Account account) {
        return !System.getProperty("user.name", "").equals(account.getUsername());
    }

    private PtyProcess direct(Account account, int columns, int rows) throws IOException {
        File shell = new File(account.getShell());
        if (!shell.isFile()) {
            throw new IOException("无法启动登录 Shell");
        }
        return new PtyProcessBuilder()
                .setCommand(new String[]{account.getShell(), "-l"})
                .setEnvironment(environment(account))
                .setDirectory(account.getHome())
                .setRedirectErrorStream(true)
                .setInitialColumns(Integer.valueOf(columns))
                .setInitialRows(Integer.valueOf(rows))
                .start();
    }

    private static Map<String, String> environment(Account account) {
        Map<String, String> env = new HashMap<String, String>();
        String lang = System.getenv("LANG");
        if (lang == null || !(lang.contains("UTF-8") || lang.contains("utf8"))) {
            lang = "en_US.UTF-8";
        }
        env.put("TERM", "xterm-256color");
        env.put("LANG", lang);
        env.put("LC_CTYPE", lang);
        env.put("HOME", account.getHome());
        env.put("USER", account.getUsername());
        env.put("LOGNAME", account.getUsername());
        env.put("SHELL", account.getShell());
        env.put("PWD", account.getHome());
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        return env;
    }
}
