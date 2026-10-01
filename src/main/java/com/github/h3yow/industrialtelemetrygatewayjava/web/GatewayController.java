package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 点位读写与一致性批量读取接口：
 * <ul>
 *   <li>GET  /api/devices                              设备列表</li>
 *   <li>GET  /api/devices/{id}/points                  批量一致性快照</li>
 *   <li>GET  /api/devices/{id}/points/{name}           单点读取</li>
 *   <li>POST /api/devices/{id}/points/{name}/writes    带版本仲裁的写入</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final TelemetryGateway gateway;

    public GatewayController(TelemetryGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/devices")
    public Map<String, Object> listDevices() {
        return Map.of("deviceIds", gateway.deviceIds());
    }

    @GetMapping("/devices/{deviceId}/points")
    public Snapshot readSnapshot(@PathVariable String deviceId) {
        return gateway.readSnapshot(deviceId);
    }

    @GetMapping("/devices/{deviceId}/points/{name}")
    public Sample readPoint(@PathVariable String deviceId, @PathVariable String name) {
        return gateway.readPoint(deviceId, name);
    }

    @PostMapping("/devices/{deviceId}/points/{name}/writes")
    public WriteResultDto write(@PathVariable String deviceId,
                                @PathVariable String name,
                                @RequestBody WriteRequestDto request) {
        Number value = request.value();
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        WriteOutcome outcome = gateway.write(
                new WriteRequest(deviceId, name, value, request.expectedVersion()));
        String reason = outcome.accepted()
                ? "CAS accepted at committedVersion=" + outcome.committedVersion()
                : "stale version: expected " + request.expectedVersion()
                        + " but actual " + outcome.actualVersion();
        return new WriteResultDto(outcome.accepted(),
                outcome.committedVersion(),
                outcome.actualVersion(),
                outcome.currentValue(),
                reason);
    }
}
