package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.BatchWriteResult;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<WriteResultDto> write(@PathVariable String deviceId,
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
        WriteResultDto body = new WriteResultDto(outcome.accepted(),
                outcome.committedVersion(),
                outcome.actualVersion(),
                outcome.currentValue(),
                reason);
        return outcome.accepted()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 原子批量写：请求体内一组点位作为一次逻辑提交，
     * 任一点位版本过期/非法则整批拒绝（HTTP 409 或对应分类错误）。
     */
    @PostMapping("/devices/{deviceId}/points/batch-writes")
    public ResponseEntity<BatchWriteResult> batchWrite(@PathVariable String deviceId,
                                                       @RequestBody List<BatchWriteItemDto> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        List<WriteRequest> requests = items.stream()
                .map(item -> {
                    if (item.name() == null || item.value() == null) {
                        throw new IllegalArgumentException("each item requires name and value");
                    }
                    return new WriteRequest(deviceId, item.name(), item.value(),
                            item.expectedVersion());
                })
                .toList();
        BatchWriteResult result = gateway.writeBatch(deviceId, requests);
        // 过期批次按冲突返回，正文仍携带逐点位仲裁结果与原因
        return result.accepted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
}
