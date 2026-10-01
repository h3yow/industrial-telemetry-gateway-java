package com.github.h3yow.industrialtelemetrygatewayjava.core;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Set;

/**
 * 点位存储：进程内版本化存储。
 * 写入采用基于版本的 CAS 仲裁；批量读取提供一致性快照。
 */
@Repository
public class PointStore {

    public PointValue get(int address) {
        return null;
    }

    /**
     * 基于期望版本的 CAS 写入。
     *
     * @return 写入成功后的新值；期望版本过期时返回 null
     */
    public PointValue compareAndSet(int address, double value, long expectedVersion) {
        return null;
    }

    /**
     * 批量一致性快照：返回的点位值都来自同一逻辑采样时刻。
     */
    public Map<Integer, PointValue> snapshot(Set<Integer> addresses) {
        return Map.of();
    }

    public Set<Integer> addresses() {
        return Set.of();
    }
}
