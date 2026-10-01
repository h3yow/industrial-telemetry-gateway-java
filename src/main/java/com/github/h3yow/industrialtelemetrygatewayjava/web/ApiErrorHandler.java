package com.github.h3yow.industrialtelemetrygatewayjava.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：把 GatewayException 映射为带错误码的 HTTP 响应。
 */
@RestControllerAdvice
public class ApiErrorHandler {
}
