package com.github.h3yow.industrialtelemetrygatewayjava.config;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.PeriodicCollector;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointDefinition;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 装配模拟通道、模拟设备、网关门面与周期采集器。
 * <p>
 * 默认提供两台设备（plc-line-1 / plc-line-2），各自若干典型点位。
 * 统一线性地址空间：线圈段 [0, 999]，保持寄存器段 [1000, 1999]。
 */
@Configuration
public class TelemetryGatewayConfig {

    static final int MIN_ADDRESS = 0;
    static final int MAX_ADDRESS = 1999;
    static final long CHANNEL_LATENCY_MILLIS = 1L;
    static final long CHANNEL_TIMEOUT_MILLIS = 200L;

    @Bean
    public TelemetryGateway telemetryGateway() {
        return new TelemetryGateway(defaultDevices());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService telemetryScheduler() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(2, r -> {
                    Thread t = new Thread(r, "telemetry-collector");
                    t.setDaemon(true);
                    return t;
                });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    public PeriodicCollector periodicCollector(ScheduledExecutorService telemetryScheduler) {
        return new PeriodicCollector(telemetryScheduler);
    }

    /** 测试与启动初始化复用：构造一组开箱即用的模拟设备。 */
    static List<SimulatedDevice> defaultDevices() {
        return List.of(
                new SimulatedDevice("plc-line-1", newChannel("plc-line-1"), line1Points()),
                new SimulatedDevice("plc-line-2", newChannel("plc-line-2"), line2Points()));
    }

    static SimulatedChannel newChannel(String deviceId) {
        return new SimulatedChannel(deviceId, MIN_ADDRESS, MAX_ADDRESS,
                CHANNEL_LATENCY_MILLIS, CHANNEL_TIMEOUT_MILLIS);
    }

    private static List<PointDefinition> line1Points() {
        return List.of(
                new PointDefinition("motor-running", 0, PointType.BOOL, 0),
                new PointDefinition("motor-speed-rpm", 1000, PointType.UINT16, 1500),
                new PointDefinition("pressure-pa", 1002, PointType.INT32, 101325),
                new PointDefinition("temperature-c", 1004, PointType.FLOAT32, 36.6f));
    }

    private static List<PointDefinition> line2Points() {
        return List.of(
                new PointDefinition("valve-open", 0, PointType.BOOL, 1),
                new PointDefinition("flow-lpm", 1000, PointType.UINT16, 420),
                new PointDefinition("level-mm", 1002, PointType.INT32, 7500),
                new PointDefinition("vibration-mm-s", 1004, PointType.FLOAT32, 0.0f));
    }
}
