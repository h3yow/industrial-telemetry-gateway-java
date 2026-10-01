package com.github.h3yow.industrialtelemetrygatewayjava.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把归一化 GatewayException 及参数异常映射为带错误分类的 JSON 响应，
 * 任何底层通道异常都不会以原始类型出现在响应中。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 骨架占位
}
