package com.jingzhou.webterminal.auth;

import com.jingzhou.webterminal.Api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final SystemAccountService accounts;
    private final LoginThrottle throttle;

    public LoginController(SystemAccountService accounts, LoginThrottle throttle) {
        this.accounts = accounts;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest request) {
        long started = System.currentTimeMillis();
        String ip = clientIp(request);
        String username = "";
        String password = "";
        if (body != null && body.u != null && body.p != null) {
            try {
                username = new String(Base64.getDecoder().decode(body.u), StandardCharsets.UTF_8).trim();
                password = new String(Base64.getDecoder().decode(body.p), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                username = "";
                password = "";
            }
        }
        if (username.isEmpty() || username.length() > 32 || password.isEmpty() || password.length() > 1024) {
            slowDown(started);
            throw new AuthFailedException("账号或密码错误");
        }
        String throttleKey = ip + "|" + username;
        if (throttle.tooMany(throttleKey)) {
            throw new TooManyAttemptsException("尝试次数过多，请稍后再试");
        }
        Account account = accounts.lookup(username);
        boolean ok = account != null && accounts.authenticate(account, password);
        if (!ok) {
            throttle.fail(throttleKey);
            log.warn("登录失败 user={} ip={}", username, ip);
            slowDown(started);
            throw new AuthFailedException("账号或密码错误");
        }
        Path home = realHome(account.getHome());
        throttle.success(throttleKey);
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(WtSession.USER, account.getUsername());
        session.setAttribute(WtSession.HOME, home.toString());
        session.setAttribute(WtSession.SHELL, account.getShell());
        if (!SystemAccountService.sameProcessUser(account.getUsername())) {
            char[] secret = password.toCharArray();
            try {
                session.setAttribute(WtSession.SECRET, new SessionSecret(secret));
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
        log.info("登录成功 user={} ip={}", account.getUsername(), ip);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("username", account.getUsername());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object secret = session.getAttribute(WtSession.SECRET);
            if (secret instanceof SessionSecret) {
                ((SessionSecret) secret).clear();
            }
            session.invalidate();
        }
        return Api.ok();
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (session == null || session.getAttribute(WtSession.USER) == null) {
            result.put("authenticated", false);
            return result;
        }
        result.put("authenticated", true);
        result.put("username", session.getAttribute(WtSession.USER));
        result.put("host", hostName());
        return result;
    }

    private static Path realHome(String home) {
        try {
            Path path = Paths.get(home).toRealPath();
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException("系统账号主目录不可用");
            }
            return path;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("系统账号主目录不可用");
        }
    }

    private static void slowDown(long started) {
        long wait = 450L - (System.currentTimeMillis() - started);
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    private static String hostName() {
        // getLocalHost() 会按主机名做 DNS 查询；主机名不在 /etc/hosts 且 DNS 不通时，请求会一直挂起。
        try {
            byte[] raw = Files.readAllBytes(Paths.get("/etc/hostname"));
            String name = new String(raw, StandardCharsets.UTF_8).trim();
            if (!name.isEmpty()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv == null || fromEnv.trim().isEmpty()) {
            fromEnv = System.getenv("COMPUTERNAME");
        }
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        return "localhost";
    }

    public static class LoginRequest {
        public String u;
        public String p;
    }

    public static class AuthFailedException extends RuntimeException {
        public AuthFailedException(String message) {
            super(message);
        }
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String message) {
            super(message);
        }
    }
}
