package com.github.h3yow.industrialtelemetrygatewayjava.service;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.ChannelException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.DeviceChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointStore;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 遥测网关服务。
 *
 * <p>职责：
 * <ul>
 *   <li>点位读写：写入先经通道校验（地址、值域），再做版本 CAS 仲裁</li>
 *   <li>批量读取：委托存储层提供一致性快照</li>
 *   <li>异常归一化：底层 {@link ChannelException} 一律转换为
 *       携带 {@link GatewayErrorCode} 的 {@link GatewayException}，不透出底层类型</li>
 *   <li>断连快速失败：通道关闭时读写立即抛出 DEVICE_UNAVAILABLE，
 *       不做任何重试或等待</li>
 * </ul>
 */
@Service
public class TelemetryGatewayService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryGatewayService.class);

    private final DeviceChannel channel;
    private final PointStore store;

    public TelemetryGatewayService(DeviceChannel channel, PointStore store) {
        this.channel = channel;
        this.store = store;
    }

    public PointValue readPoint(int address) {
        failFastIfUnavailable();
        checkAddress(address);
        PointValue value = store.get(address);
        if (value == null) {
            throw new GatewayException(GatewayErrorCode.POINT_NOT_FOUND,
                    "point " + address + " has not been acquired yet");
        }
        return value;
    }

    /**
     * 基于期望版本的写入仲裁。
     * 先由通道校验地址与值域，再做 CAS：期望版本过期时抛出 STALE_VERSION。
     */
    public PointValue writePoint(int address, double value, long expectedVersion) {
        failFastIfUnavailable();
        try {
            channel.write(address, value);
        } catch (ChannelException e) {
            throw normalize(e);
        }
        PointValue written = store.compareAndSet(address, value, expectedVersion);
        if (written == null) {
            PointValue current = store.get(address);
            throw new GatewayException(GatewayErrorCode.STALE_VERSION,
                    "stale write rejected: expectedVersion=" + expectedVersion
                            + ", currentVersion=" + (current == null ? "none" : current.version())
                            + ", address=" + address);
        }
        return written;
    }

    /**
     * 批量读取：返回的点位来自同一逻辑采样时刻（存储层 seqlock 快照）。
     */
    public Map<Integer, PointValue> readBatch(Set<Integer> addresses) {
        failFastIfUnavailable();
        for (int address : addresses) {
            checkAddress(address);
        }
        Map<Integer, PointValue> snapshot = store.snapshot(addresses);
        for (int address : addresses) {
            if (!snapshot.containsKey(address)) {
                throw new GatewayException(GatewayErrorCode.POINT_NOT_FOUND,
                        "point " + address + " has not been acquired yet");
            }
        }
        return snapshot;
    }

    /**
     * 执行一轮周期采集：采集版本在读取设备之前分配，
     * 保证并发的客户端写入不会被旧采样覆盖。通道异常时放弃本轮。
     */
    public void acquireOnce() {
        if (!channel.isOpen()) {
            log.warn("acquisition skipped: device channel unavailable");
            return;
        }
        long acquisitionVersion = store.nextVersion();
        Instant sampledAt = Instant.now();
        Map<Integer, PointValue> sampled = new HashMap<>();
        try {
            for (int address = 0; address < channel.addressCount(); address++) {
                sampled.put(address, new PointValue(channel.read(address), acquisitionVersion, sampledAt));
            }
        } catch (ChannelException e) {
            log.warn("acquisition cycle aborted: {}", e.getMessage());
            return;
        }
        store.mergeAcquisition(sampled);
    }

    private void failFastIfUnavailable() {
        if (!channel.isOpen()) {
            throw new GatewayException(GatewayErrorCode.DEVICE_UNAVAILABLE,
                    "device channel is closed, failing fast");
        }
    }

    private void checkAddress(int address) {
        if (address < 0 || address >= channel.addressCount()) {
            throw new GatewayException(GatewayErrorCode.INVALID_ADDRESS,
                    "address " + address + " out of range [0," + channel.addressCount() + ")");
        }
    }

    /** 底层通道异常归一化：只暴露错误码与描述，不透出底层异常类型 */
    private GatewayException normalize(ChannelException e) {
        if (e instanceof ChannelException.Timeout) {
            return new GatewayException(GatewayErrorCode.CHANNEL_TIMEOUT, "channel read/write timed out");
        }
        if (e instanceof ChannelException.InvalidAddress) {
            return new GatewayException(GatewayErrorCode.INVALID_ADDRESS, e.getMessage());
        }
        if (e instanceof ChannelException.ValueOutOfRange) {
            return new GatewayException(GatewayErrorCode.VALUE_OUT_OF_RANGE, e.getMessage());
        }
        if (e instanceof ChannelException.Closed) {
            return new GatewayException(GatewayErrorCode.DEVICE_UNAVAILABLE, "device channel is closed");
        }
        return new GatewayException(GatewayErrorCode.DEVICE_UNAVAILABLE, "unclassified channel failure");
    }
}
