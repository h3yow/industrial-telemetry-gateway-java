package com.github.h3yow.industrialtelemetrygatewayjava.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * 设备生命周期（供演示断连快速失败场景）：
 * POST /api/devices/{id}/connect
 * POST /api/devices/{id}/disconnect
 */
@RestController
public class DeviceAdminController {

    public DeviceAdminController() {
        // 骨架占位
    }

    public Object connect(String deviceId) {
        return null;
    }

    public Object disconnect(String deviceId) {
        return null;
    }
}
