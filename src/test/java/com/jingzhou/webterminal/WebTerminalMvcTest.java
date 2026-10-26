package com.jingzhou.webterminal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebTerminalMvcTest {

    @Autowired
    private TestRestTemplate rest;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Test
    void contextPathIsWt() {
        assertEquals("/wt", contextPath);
    }

    @Test
    void homeShowsNginxWelcomeWithoutLoginForm() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("Welcome to nginx!"));
        assertTrue(body.contains("community.nginx.org"));
        assertTrue(body.contains("f5.com/nginx"));
        assertTrue(body.contains("Thank you for using nginx."));
        assertFalse(body.contains("type=\"password\""));
        assertFalse(body.contains("登录"));
        assertEquals("nginx", response.getHeaders().getFirst("Server"));
    }

    @Test
    void filesRequireLogin() {
        ResponseEntity<String> response = rest.getForEntity("/api/files", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("未登录"));
    }

    @Test
    void unknownAccountIsRejected() {
        ResponseEntity<String> response = postLogin(loginJson("no-such-user", "x"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("账号或密码错误"));
    }

    @Test
    void malformedLoginIsRejected() {
        ResponseEntity<String> legacy = postLogin("{\"username\":\"no-such-user\",\"password\":\"x\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, legacy.getStatusCode());
        assertTrue(legacy.getBody().contains("账号或密码错误"));

        ResponseEntity<String> invalid = postLogin("{\"u\":\"!!!\",\"p\":\"!!!\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, invalid.getStatusCode());
        assertTrue(invalid.getBody().contains("账号或密码错误"));

        ResponseEntity<String> empty = postLogin("{\"u\":\"\",\"p\":\"\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, empty.getStatusCode());
        assertTrue(empty.getBody().contains("账号或密码错误"));
    }

    private ResponseEntity<String> postLogin(String json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        rest.getRestTemplate().setRequestFactory(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/login", new HttpEntity<String>(json, headers), String.class);
    }

    private static String loginJson(String username, String password) {
        return "{\"u\":\"" + base64(username) + "\",\"p\":\"" + base64(password) + "\"}";
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
