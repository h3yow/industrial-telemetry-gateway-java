package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/** 写入值超出寄存器可表示范围。 */
public class ValueOutOfBoundsException extends SimulatedChannelException {
    public ValueOutOfBoundsException(String message) {
        super(message);
    }
}
