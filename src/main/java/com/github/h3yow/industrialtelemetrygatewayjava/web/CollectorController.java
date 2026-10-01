package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.CollectedSnapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.PeriodicCollector;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.TelemetryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledFuture;

/**
 * 周期采集管理接口：
 * <ul>
 *   <li>POST /api/collector/start?deviceId=&amp;intervalMillis=</li>
 *   <li>POST /api/collector/stop?deviceId=</li>
 *   <li>GET  /api/collector/frames?deviceId=   最近采集帧（每台设备保留最新 64 帧）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/collector")
public class CollectorController {

    private static final int MAX_FRAMES_PER_DEVICE = 64;

    private final PeriodicCollector collector;
    private final TelemetryGateway gateway;
    private final Map<String, ScheduledFuture<?>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<CollectedSnapshot>> frames =
            new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    public CollectorController(PeriodicCollector collector, TelemetryGateway gateway) {
        this.collector = collector;
        this.gateway = gateway;
    }

    @PostMapping("/start")
    public synchronized Map<String, Object> start(@RequestParam String deviceId,
                                                  @RequestParam long intervalMillis) {
        SimulatedDevice device = gateway.requireDevice(deviceId);
        ScheduledFuture<?> existing = subscriptions.get(deviceId);
        if (existing != null && !existing.isDone()) {
            throw new IllegalStateException("collection already running for " + deviceId);
        }
        ConcurrentLinkedDeque<CollectedSnapshot> buffer =
                frames.computeIfAbsent(deviceId, k -> new ConcurrentLinkedDeque<>());
        ScheduledFuture<?> future = collector.subscribe(device, intervalMillis,
                frame -> {
                    buffer.addLast(frame);
                    while (buffer.size() > MAX_FRAMES_PER_DEVICE) {
                        buffer.pollFirst();
                    }
                },
                error -> lastError.put(deviceId,
                        error.getClass().getSimpleName() + ": " + error.getMessage()));
        subscriptions.put(deviceId, future);
        return Map.of("deviceId", deviceId, "intervalMillis", intervalMillis, "running", true);
    }

    @PostMapping("/stop")
    public synchronized Map<String, Object> stop(@RequestParam String deviceId) {
        ScheduledFuture<?> future = subscriptions.remove(deviceId);
        if (future != null) {
            collector.unsubscribe(future);
        }
        return Map.of("deviceId", deviceId, "running", false);
    }

    @GetMapping("/frames")
    public Map<String, Object> recentFrames(@RequestParam String deviceId) {
        gateway.requireDevice(deviceId);
        List<CollectedSnapshot> snapshot =
                List.copyOf(frames.getOrDefault(deviceId, new ConcurrentLinkedDeque<>()));
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("deviceId", deviceId);
        body.put("frames", snapshot);
        body.put("lastError", lastError.get(deviceId));
        return body;
    }
}
