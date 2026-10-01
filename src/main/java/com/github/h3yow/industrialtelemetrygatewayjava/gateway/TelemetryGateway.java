package com.github.h3yow.industrialtelemetrygatewayjava.gateway;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;

import java.util.List;
import java.util.Optional;

/**
 * 网关门面：管理多台模拟设备，提供点位读写、一致性批量读取与设备生命周期操作。
 * 异常在此保持归一化形态（GatewayException），不出现任何通道原始异常。
 */
public class TelemetryGateway {

    public TelemetryGateway(List<SimulatedDevice> devices) {
        // 骨架占位
    }

    public void registerDevice(SimulatedDevice device) {
        // 骨架占位
    }

    public List<String> deviceIds() {
        return List.of();
    }

    public SimulatedDevice requireDevice(String deviceId) {
        return null;
    }

    public Optional<SimulatedDevice> device(String deviceId) {
        return Optional.empty();
    }

    public Sample readPoint(String deviceId, String name) {
        return null;
    }

    public Snapshot readSnapshot(String deviceId) {
        return null;
    }

    public WriteOutcome write(WriteRequest request) {
        return null;
    }
}
