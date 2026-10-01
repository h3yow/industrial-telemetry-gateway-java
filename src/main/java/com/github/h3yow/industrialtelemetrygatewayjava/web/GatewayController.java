package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点位读写与一致性批量读取接口：
 * GET  /api/devices                         设备列表
 * GET  /api/devices/{id}/points             批量一致性快照
 * GET  /api/devices/{id}/points/{name}      单点读取
 * POST /api/devices/{id}/points/{name}/writes  带版本仲裁的写入
 */
@RestController
public class GatewayController {

    public GatewayController(TelemetryGateway gateway) {
        // 骨架占位
    }

    public Object listDevices() {
        return null;
    }

    public Snapshot readSnapshot(String deviceId) {
        return null;
    }

    public Sample readPoint(String deviceId, String name) {
        return null;
    }

    public Object write(String deviceId, String name, WriteRequestDto request) {
        return null;
    }
}
