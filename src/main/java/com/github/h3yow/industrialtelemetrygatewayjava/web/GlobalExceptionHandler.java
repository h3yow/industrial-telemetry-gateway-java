package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 把归一化 {@link GatewayException} 及参数异常映射为带错误分类的 JSON 响应。
 * 任何底层通道异常都不会以原始类型出现在响应中：未预期异常统一收敛为
 * INTERNAL_ERROR，且不回传异常堆栈/原始类名。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException e) {
        HttpStatus status = switch (e.category()) {
            case DEVICE_NOT_FOUND, INVALID_ADDRESS -> HttpStatus.NOT_FOUND;
            case STALE_VERSION -> HttpStatus.CONFLICT;
            case VALUE_OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case DEVICE_DISCONNECTED, IO_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("gateway error -> http {} category={} message={}",
                status.value(), e.category(), e.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.category().name(), e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCategory.VALUE_OUT_OF_RANGE.name(),
                        "bad request: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error, normalized to INTERNAL_ERROR", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCategory.INTERNAL_ERROR.name(),
                        "internal gateway error"));
    }
}
