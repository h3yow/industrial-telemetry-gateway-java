package com.github.h3yow.industrialtelemetrygatewayjava.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * 周期采集管理接口：
 * POST /api/collector/start?deviceId=&intervalMillis=
 * POST /api/collector/stop
 * GET  /api/collector/frames   最近采集帧（内存环形缓冲）
 */
@RestController
public class CollectorController {

    public CollectorController() {
        // 骨架占位
    }

    public Object start(String deviceId, long intervalMillis) {
        return null;
    }

    public Object stop() {
        return null;
    }

    public Object recentFrames() {
        return null;
    }
}
