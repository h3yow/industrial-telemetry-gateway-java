package com.github.h3yow.industrialtelemetrygatewayjava.channel;

import org.springframework.stereotype.Component;

/**
 * 进程内模拟设备通道：用内存数组替代真实 PLC，
 * 支持地址校验、值域校验、模拟延迟与故障注入。
 */
@Component
public class SimulatedDeviceChannel implements DeviceChannel {

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public double read(int address) throws ChannelException {
        return 0;
    }

    @Override
    public void write(int address, double value) throws ChannelException {
    }

    @Override
    public int addressCount() {
        return 0;
    }

    @Override
    public void close() {
    }
}
