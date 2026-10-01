package com.github.h3yow.industrialtelemetrygatewayjava.gateway;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.model.BatchWriteResult;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网关门面：管理多台模拟设备，提供点位读写、一致性批量读取与设备查找。
 * 异常在此保持归一化形态（GatewayException），不出现任何通道原始异常。
 */
public class TelemetryGateway {

    private final Map<String, SimulatedDevice> devices = new LinkedHashMap<>();

    public TelemetryGateway(List<SimulatedDevice> devices) {
        for (SimulatedDevice device : devices) {
            registerDevice(device);
        }
    }

    public final void registerDevice(SimulatedDevice device) {
        devices.put(device.deviceId(), device);
    }

    public List<String> deviceIds() {
        return List.copyOf(devices.keySet());
    }

    public Optional<SimulatedDevice> device(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public SimulatedDevice requireDevice(String deviceId) {
        SimulatedDevice device = devices.get(deviceId);
        if (device == null) {
            throw new GatewayException(ErrorCategory.DEVICE_NOT_FOUND,
                    "device not found: " + deviceId);
        }
        return device;
    }

    public Sample readPoint(String deviceId, String name) {
        return requireDevice(deviceId).readPoint(name);
    }

    public Snapshot readSnapshot(String deviceId) {
        return requireDevice(deviceId).readSnapshot();
    }

    public WriteOutcome write(WriteRequest domain) {
        return requireDevice(domain.deviceId()).write(domain);
    }

    /** 原子批量写：一批点位一次提交，全部成功或全部不生效。 */
    public BatchWriteResult writeBatch(String deviceId, List<WriteRequest> requests) {
        return requireDevice(deviceId).writeBatch(requests);
    }
}
