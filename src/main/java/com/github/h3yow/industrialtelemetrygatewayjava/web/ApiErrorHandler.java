package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayErrorCode;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.EnumMap;
import java.util.Map;

/**
 * 统一错误响应：把 GatewayException 映射为带错误码的 HTTP 响应，
 * 调用方凭 code 区分超时、非法地址、越界、断连与版本冲突。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Map<GatewayErrorCode, HttpStatus> STATUS = new EnumMap<>(GatewayErrorCode.class);

    static {
        STATUS.put(GatewayErrorCode.STALE_VERSION, HttpStatus.CONFLICT);
        STATUS.put(GatewayErrorCode.INVALID_ADDRESS, HttpStatus.BAD_REQUEST);
        STATUS.put(GatewayErrorCode.VALUE_OUT_OF_RANGE, HttpStatus.BAD_REQUEST);
        STATUS.put(GatewayErrorCode.POINT_NOT_FOUND, HttpStatus.NOT_FOUND);
        STATUS.put(GatewayErrorCode.DEVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        STATUS.put(GatewayErrorCode.CHANNEL_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handle(GatewayException e) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(status).body(new ErrorResponse(e.code().name(), e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {
    }
}
