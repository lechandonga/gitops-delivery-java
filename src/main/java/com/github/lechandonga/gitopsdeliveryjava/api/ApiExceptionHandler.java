package com.github.lechandonga.gitopsdeliveryjava.api;

import com.github.lechandonga.gitopsdeliveryjava.engine.DeliveryRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 拒绝类异常映射为可区分原因码（422），与系统错误（500）区分。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DeliveryRejectedException.class)
    public ResponseEntity<ApiDtos.RejectedResponse> rejected(DeliveryRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiDtos.RejectedResponse(ex.reasonCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.RejectedResponse> badArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiDtos.RejectedResponse("BAD_REQUEST", ex.getMessage()));
    }
}
