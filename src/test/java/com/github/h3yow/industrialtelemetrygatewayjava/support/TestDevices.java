package com.github.h3yow.industrialtelemetrygatewayjava.support;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointDefinition;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointType;

import java.util.List;

/**
 * 测试夹具：零延迟、不会超时的通道 + 一组覆盖四种类型的点位。
 */
public final class TestDevices {

    public static final int MIN_ADDRESS = 0;
    public static final int MAX_ADDRESS = 1999;

    private TestDevices() {
    }

    public static SimulatedChannel newChannel(String deviceId) {
        return new SimulatedChannel(deviceId, MIN_ADDRESS, MAX_ADDRESS, 0L, 10_000L);
    }

    public static List<PointDefinition> points() {
        return List.of(
                new PointDefinition("motor-running", 0, PointType.BOOL, 0),
                new PointDefinition("motor-speed-rpm", 1000, PointType.UINT16, 100),
                new PointDefinition("pressure-pa", 1002, PointType.INT32, 1000),
                new PointDefinition("temperature-c", 1004, PointType.FLOAT32, 20.0f));
    }

    public static SimulatedDevice newDevice(String deviceId) {
        return new SimulatedDevice(deviceId, newChannel(deviceId), points());
    }
}
