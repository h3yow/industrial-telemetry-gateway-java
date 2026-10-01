package com.github.h3yow.industrialtelemetrygatewayjava.service;

/**
 * 网关对外统一异常：携带错误码，不暴露底层通道异常类型。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode code;

    public GatewayException(GatewayErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public GatewayErrorCode code() {
        return code;
    }
}
