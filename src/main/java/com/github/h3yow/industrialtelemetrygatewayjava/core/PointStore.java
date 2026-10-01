package com.github.h3yow.industrialtelemetrygatewayjava.core;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 点位存储：进程内版本化存储。
 *
 * <p>并发与一致性策略：
 * <ul>
 *   <li>客户端写入采用基于版本的 CAS 仲裁：期望版本不匹配即拒绝，
 *       因此并发写入的最终状态由 CAS 顺序决定，是确定的。</li>
 *   <li>批量读取采用 seqlock 模式：读取前后各取一次修改计数，
 *       计数不变则证明读取期间没有写入，快照一致；否则重试。</li>
 *   <li>周期采集以“保留最大版本”的方式合并，采集版本在读取设备前
 *       分配，保证并发的客户端写入不会被旧采样覆盖。</li>
 * </ul>
 */
@Repository
public class PointStore {

    private static final int MAX_SNAPSHOT_RETRIES = 10_000;

    private final ConcurrentHashMap<Integer, PointValue> entries = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);
    private final AtomicLong modificationCount = new AtomicLong(0);

    public PointValue get(int address) {
        return entries.get(address);
    }

    /**
     * 基于期望版本的 CAS 写入。
     *
     * @return 写入成功后的新值；点位不存在或期望版本过期时返回 null
     */
    public PointValue compareAndSet(int address, double value, long expectedVersion) {
        while (true) {
            PointValue current = entries.get(address);
            if (current == null || current.version() != expectedVersion) {
                return null;
            }
            PointValue next = new PointValue(value, versionCounter.incrementAndGet(), Instant.now());
            if (entries.replace(address, current, next)) {
                modificationCount.incrementAndGet();
                return next;
            }
        }
    }

    /**
     * 合并一轮采集结果。采集版本必须在读取设备之前分配，
     * 每个点位保留版本较大的值，避免旧采样覆盖新写入。
     */
    public void mergeAcquisition(Map<Integer, PointValue> sampled) {
        sampled.forEach((address, sampledValue) ->
                entries.merge(address, sampledValue,
                        (existing, incoming) -> incoming.version() > existing.version() ? incoming : existing));
        modificationCount.incrementAndGet();
    }

    /**
     * 批量一致性快照（seqlock）：返回的点位值读取期间没有任何写入发生。
     */
    public Map<Integer, PointValue> snapshot(Set<Integer> addresses) {
        for (int attempt = 0; attempt < MAX_SNAPSHOT_RETRIES; attempt++) {
            long before = modificationCount.get();
            Map<Integer, PointValue> result = new HashMap<>();
            for (int address : addresses) {
                PointValue value = entries.get(address);
                if (value != null) {
                    result.put(address, value);
                }
            }
            long after = modificationCount.get();
            if (before == after) {
                return result;
            }
        }
        throw new IllegalStateException("unable to obtain a consistent snapshot, writes too frequent");
    }

    public Set<Integer> addresses() {
        return Set.copyOf(entries.keySet());
    }

    /** 分配下一个全局版本号（采集周期与客户端写入共用同一计数器） */
    public long nextVersion() {
        return versionCounter.incrementAndGet();
    }
}
