package com.github.h3yow.industrialtelemetrygatewayjava.web;

import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import com.github.h3yow.industrialtelemetrygatewayjava.service.TelemetryGatewayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 遥测 REST 接口：点位读写与批量读取。
 */
@RestController
@RequestMapping("/api/points")
public class TelemetryController {

    private final TelemetryGatewayService gatewayService;

    public TelemetryController(TelemetryGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @GetMapping("/{address}")
    public PointResponse read(@PathVariable int address) {
        return PointResponse.of(address, gatewayService.readPoint(address));
    }

    @PutMapping("/{address}")
    public PointResponse write(@PathVariable int address, @RequestBody WriteRequest request) {
        return PointResponse.of(address,
                gatewayService.writePoint(address, request.value(), request.expectedVersion()));
    }

    @GetMapping
    public Map<Integer, PointResponse> readBatch(@RequestParam List<Integer> addresses) {
        return gatewayService.readBatch(Set.copyOf(addresses)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> PointResponse.of(e.getKey(), e.getValue()),
                        (a, b) -> a, LinkedHashMap::new));
    }

    public record WriteRequest(double value, long expectedVersion) {
    }

    public record PointResponse(int address, double value, long version, Instant sampledAt) {
        static PointResponse of(int address, PointValue value) {
            return new PointResponse(address, value.value(), value.version(), value.sampledAt());
        }
    }
}
