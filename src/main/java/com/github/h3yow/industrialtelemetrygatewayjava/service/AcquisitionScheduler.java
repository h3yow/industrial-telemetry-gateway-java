package com.github.h3yow.industrialtelemetrygatewayjava.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期采集调度：按固定间隔触发一轮全点位采集，
 * 每轮采集的所有点位共享同一采样时刻与采集版本。
 */
@Component
public class AcquisitionScheduler {

    private final TelemetryGatewayService gatewayService;

    public AcquisitionScheduler(TelemetryGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Scheduled(
            fixedDelayString = "${gateway.acquisition.interval-ms:1000}",
            initialDelayString = "${gateway.acquisition.initial-delay-ms:0}")
    public void run() {
        gatewayService.acquireOnce();
    }
}
