package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备生命周期（供演示断连快速失败场景）：
 * <ul>
 *   <li>POST /api/devices/{id}/connect</li>
 *   <li>POST /api/devices/{id}/disconnect</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
public class DeviceAdminController {

    private final TelemetryGateway gateway;

    public DeviceAdminController(TelemetryGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(@PathVariable String deviceId) {
        SimulatedDevice device = gateway.requireDevice(deviceId);
        device.connect();
        return Map.of("deviceId", deviceId, "connected", true);
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@PathVariable String deviceId) {
        SimulatedDevice device = gateway.requireDevice(deviceId);
        device.disconnect();
        return Map.of("deviceId", deviceId, "connected", false);
    }
}
