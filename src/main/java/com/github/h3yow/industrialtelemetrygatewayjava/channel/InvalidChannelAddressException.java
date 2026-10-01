package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/** 访问了通道上不存在的寄存器地址。 */
public class InvalidChannelAddressException extends SimulatedChannelException {
    public InvalidChannelAddressException(String message) {
        super(message);
    }
}
