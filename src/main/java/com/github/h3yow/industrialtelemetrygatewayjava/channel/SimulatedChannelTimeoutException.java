package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/** 底层读/写超时（对应 Modbus/现场总线无响应）。 */
public class SimulatedChannelTimeoutException extends SimulatedChannelException {
    public SimulatedChannelTimeoutException(String message) {
        super(message);
    }
}
