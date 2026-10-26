package com.jingzhou.webterminal.config;

import com.jingzhou.webterminal.Api;
import com.jingzhou.webterminal.auth.LoginController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LoginController.AuthFailedException.class)
    public ResponseEntity<Map<String, Object>> authFailed(LoginController.AuthFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Api.fail(ex.getMessage()));
    }

    @ExceptionHandler(LoginController.TooManyAttemptsException.class)
    public ResponseEntity<Map<String, Object>> tooMany(LoginController.TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Api.fail(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Api.fail(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        if ("未登录".equals(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Api.fail(ex.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Api.fail(ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Api.fail("文件过大"));
    }
}
