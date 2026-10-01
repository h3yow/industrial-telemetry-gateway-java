package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/**
 * 设备通道抽象：对底层 PLC / 现场总线的最小读写接口。
 */
public interface DeviceChannel {

    boolean isOpen();

    double read(int address) throws ChannelException;

    void write(int address, double value) throws ChannelException;

    int addressCount();

    void close();
}
