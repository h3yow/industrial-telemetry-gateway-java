package com.github.h3yow.industrialtelemetrygatewayjava.web;

import java.util.List;

/**
 * 归一化错误响应：只有错误分类与可读原因，不含底层通道异常类名。
 */
public record ErrorResponse(String category, String message, List<String> details) {
    public ErrorResponse(String category, String message) {
        this(category, message, List.of());
    }
}
