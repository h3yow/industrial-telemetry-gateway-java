package com.github.h3yow.industrialtelemetrygatewayjava.config;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.PeriodicCollector;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 装配模拟通道、模拟设备、网关门面与周期采集器。
 * <p>
 * 默认提供两台设备（plc-line-1 / plc-line-2），各自若干典型点位。
 */
@Configuration
public class TelemetryGatewayConfig {

    @Bean
    public TelemetryGateway telemetryGateway() {
        return null;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService telemetryScheduler() {
        return new ScheduledThreadPoolExecutor(1);
    }

    @Bean(destroyMethod = "shutdownNow")
    public PeriodicCollector periodicCollector(ScheduledExecutorService telemetryScheduler) {
        return null;
    }

    /** 测试与启动初始化复用：构造一组开箱即用的模拟设备。 */
    static List<SimulatedDevice> defaultDevices() {
        return List.of();
    }

    static SimulatedChannel newChannel(String deviceId) {
        return null;
    }
}
