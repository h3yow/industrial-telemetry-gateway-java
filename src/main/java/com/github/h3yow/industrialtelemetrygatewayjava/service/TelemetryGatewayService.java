package com.github.h3yow.industrialtelemetrygatewayjava.service;

import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 遥测网关服务：点位读写、并发写入仲裁、批量一致性快照、
 * 底层通道异常归一化与断连快速失败。
 */
@Service
public class TelemetryGatewayService {

    public PointValue readPoint(int address) {
        return null;
    }

    /**
     * 基于期望版本的写入仲裁：版本过期时抛出 STALE_VERSION。
     */
    public PointValue writePoint(int address, double value, long expectedVersion) {
        return null;
    }

    /**
     * 批量读取：返回的点位来自同一逻辑采样时刻。
     */
    public Map<Integer, PointValue> readBatch(Set<Integer> addresses) {
        return Map.of();
    }

    /**
     * 供周期采集调用：执行一轮采集并写入存储。
     */
    public void acquireOnce() {
    }
}
