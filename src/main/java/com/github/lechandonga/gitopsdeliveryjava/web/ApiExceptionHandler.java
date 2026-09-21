package com.github.lechandonga.gitopsdeliveryjava.web;

import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将业务拒绝映射为可区分的 HTTP 响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DeliveryRejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(DeliveryRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", ex.getReasonCode().name(), "message", ex.getMessage()));
    }
}
