package com.github.h3yow.industrialtelemetrygatewayjava.error;

/**
 * 网关归一化异常：对客户端只暴露 {@link ErrorCategory} 与可读原因，
 * 不直接透出底层通道异常类型。
 */
public class GatewayException extends RuntimeException {

    private final ErrorCategory category;

    public GatewayException(ErrorCategory category, String message) {
        super(message);
        this.category = category;
    }

    public GatewayException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public ErrorCategory category() {
        return category;
    }
}
