package com.github.h3yow.industrialtelemetrygatewayjava.core;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 点位存储：进程内版本化存储。
 *
 * <p>并发与一致性策略：
 * <ul>
 *   <li>客户端写入采用基于版本的 CAS 仲裁：期望版本不匹配即拒绝，
 *       因此并发写入的最终状态由仲裁顺序决定，是确定的；
 *       版本号在原子块内、确认成功后才会分配，每次成功写入版本恰好前进 1。</li>
 *   <li>批量读取与写入/采集合并之间使用公平读写锁互斥：
 *       快照在写锁保护的关键区之外不可能观察到写了一半的状态，
 *       公平策略保证持续写入下读取者不会饿死。</li>
 *   <li>周期采集以“保留最大版本”的方式合并，采集版本在读取设备前
 *       分配，保证并发的客户端写入不会被旧采样覆盖。</li>
 * </ul>
 */
@Repository
public class PointStore {

    private final ConcurrentHashMap<Integer, PointValue> entries = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);
    private final ReentrantReadWriteLock consistencyLock = new ReentrantReadWriteLock(true);

    public PointValue get(int address) {
        return entries.get(address);
    }

    /**
     * 基于期望版本的 CAS 写入。
     *
     * @return 写入成功后的新值；点位不存在或期望版本过期时返回 null
     */
    public PointValue compareAndSet(int address, double value, long expectedVersion) {
        PointValue[] written = new PointValue[1];
        consistencyLock.writeLock().lock();
        try {
            entries.compute(address, (key, current) -> {
                if (current == null || current.version() != expectedVersion) {
                    return current;
                }
                written[0] = new PointValue(value, versionCounter.incrementAndGet(), Instant.now());
                return written[0];
            });
        } finally {
            consistencyLock.writeLock().unlock();
        }
        return written[0];
    }

    /**
     * 合并一轮采集结果。采集版本必须在读取设备之前分配，
     * 每个点位保留版本较大的值，避免旧采样覆盖新写入。
     */
    public void mergeAcquisition(Map<Integer, PointValue> sampled) {
        consistencyLock.writeLock().lock();
        try {
            sampled.forEach((address, sampledValue) ->
                    entries.merge(address, sampledValue,
                            (existing, incoming) -> incoming.version() > existing.version() ? incoming : existing));
        } finally {
            consistencyLock.writeLock().unlock();
        }
    }

    /**
     * 批量一致性快照：在读锁保护下一次性读取全部点位，
     * 返回的组合必然在某一时刻真实共存过。
     */
    public Map<Integer, PointValue> snapshot(Set<Integer> addresses) {
        consistencyLock.readLock().lock();
        try {
            Map<Integer, PointValue> result = new HashMap<>();
            for (int address : addresses) {
                PointValue value = entries.get(address);
                if (value != null) {
                    result.put(address, value);
                }
            }
            return result;
        } finally {
            consistencyLock.readLock().unlock();
        }
    }

    public Set<Integer> addresses() {
        return Set.copyOf(entries.keySet());
    }

    /** 分配下一个全局版本号（采集周期与客户端写入共用同一计数器） */
    public long nextVersion() {
        return versionCounter.incrementAndGet();
    }
}
