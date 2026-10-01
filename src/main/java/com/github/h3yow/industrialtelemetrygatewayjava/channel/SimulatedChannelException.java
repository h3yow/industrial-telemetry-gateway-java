package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/**
 * 模拟通道底层异常的基类。
 * <p>
 * 此类及其子类代表“现场总线/PLC 驱动”层面的原始错误，
 * 只允许在通道层内部出现，设备层必须将其归一化为 GatewayException，
 * 不允许原样透传给上层与客户端。
 */
public class SimulatedChannelException extends RuntimeException {
    public SimulatedChannelException(String message) {
        super(message);
    }

    public SimulatedChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
