package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/** 通道已关闭（设备断连后仍被调用）。 */
public class ChannelClosedException extends SimulatedChannelException {
    public ChannelClosedException(String message) {
        super(message);
    }
}
