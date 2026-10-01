package com.github.h3yow.industrialtelemetrygatewayjava.device;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.ChannelClosedException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.InvalidChannelAddressException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannelException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannelTimeoutException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.ValueOutOfBoundsException;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.model.BatchWriteResult;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointDefinition;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointType;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内模拟设备：点位视图 + 乐观锁版本 + 一致性快照 + 通道异常归一化。
 * <p>
 * 并发策略：
 * <ul>
 *   <li>设备级 {@link ReentrantReadWriteLock}：所有读（单点/批量快照）共享读锁，
 *       所有写独占写锁。批量快照在同一次读锁持有期间对全部点位逐个采样，
 *       因而与任何写入互斥，快照内所有点位必然来自同一采样时刻、同一个 commitSeq。</li>
 *   <li>写仲裁采用乐观版本号（CAS）：客户端携带 expectedVersion，
 *       版本不匹配即返回 rejected，绝不静默覆盖；并发写入由写锁串行化，
 *       因此最终状态确定且可复现。</li>
 *   <li>每次被接受的写入把点位 version +1，并推进单调全局 commitSeq。</li>
 * </ul>
 * <p>
 * 快速失败：设备断连后，任何读写在触碰通道前立即抛 DEVICE_DISCONNECTED。
 * <p>
 * 异常归一化：通道原始异常仅作为 cause 记录日志，对外只出现 {@link GatewayException}
 * 及其 {@link ErrorCategory}。
 */
public class SimulatedDevice {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDevice.class);

    private final String deviceId;
    private final SimulatedChannel channel;
    private final Map<String, PointDefinition> pointsByName = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Long> versions = new HashMap<>();
    private volatile long commitSeq = 0L;
    private volatile boolean connected;

    /**
     * 提交监听器：在写锁内、状态提交之后、锁释放之前同步回调。
     * 参数为提交后的全局 commitSeq。供需要与设备状态严格同步的观察者使用，
     * 避免“锁外事后登记”造成的观察竞态。
     */
    public interface CommitListener {
        void onCommitted(long commitSeq);
    }

    private volatile CommitListener commitListener;

    public void setCommitListener(CommitListener listener) {
        this.commitListener = listener;
    }

    public SimulatedDevice(String deviceId, SimulatedChannel channel, List<PointDefinition> points) {
        this.deviceId = deviceId;
        this.channel = channel;
        for (PointDefinition point : points) {
            if (pointsByName.put(point.name(), point) != null) {
                throw new IllegalArgumentException("duplicated point name: " + point.name());
            }
            versions.put(point.name(), 0L);
        }
        this.connected = false;
        // 建立底层连接并把初始值写入寄存器/线圈
        channel.open();
        this.connected = true;
        for (PointDefinition point : points) {
            writeToChannel(point, point.initialValue());
        }
        log.info("[{}] simulated device online with {} point(s)", deviceId, points.size());
    }

    public String deviceId() {
        return deviceId;
    }

    public boolean isConnected() {
        return connected;
    }

    /** 建立连接（打开通道）。 */
    public void connect() {
        lock.writeLock().lock();
        try {
            channel.open();
            connected = true;
            log.info("[{}] device connected", deviceId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 断开连接（关闭通道）；之后读写快速失败。 */
    public void disconnect() {
        lock.writeLock().lock();
        try {
            channel.close();
            connected = false;
            log.warn("[{}] device disconnected, subsequent reads/writes fail fast", deviceId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PointDefinition findPoint(String name) {
        PointDefinition point = pointsByName.get(name);
        if (point == null) {
            throw new GatewayException(ErrorCategory.INVALID_ADDRESS,
                    "point not found: " + name + " on device " + deviceId);
        }
        return point;
    }

    /** 单点读取（读锁内采样）。 */
    public Sample readPoint(String name) {
        PointDefinition point = findPoint(name);
        lock.readLock().lock();
        try {
            Number value = readFromChannel(point);
            return new Sample(point.name(), point.address(), value, versions.get(point.name()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 一致性批量读取：同一读锁内完成，所有点位同属一个 commitSeq。 */
    public Snapshot readSnapshot() {
        lock.readLock().lock();
        try {
            requireConnected();
            long seq = commitSeq;
            long timestamp = System.currentTimeMillis();
            List<PointDefinition> ordered = new ArrayList<>(pointsByName.values());
            ordered.sort(Comparator.comparingInt(PointDefinition::address));
            List<Sample> samples = new ArrayList<>(ordered.size());
            for (PointDefinition point : ordered) {
                Number value = readFromChannel(point);
                samples.add(new Sample(point.name(), point.address(), value,
                        versions.get(point.name())));
            }
            return new Snapshot(deviceId, timestamp, seq, List.copyOf(samples));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 带乐观锁的点位写入仲裁。
     * <p>
     * 校验顺序：点位存在 → 设备在线（快速失败）→ 版本 CAS（过期拒绝，不触通道）
     * → 值编码/越界 → 通道写入 → 提交版本与 commitSeq。
     */
    public WriteOutcome write(WriteRequest request) {
        PointDefinition point = findPoint(request.name());
        lock.writeLock().lock();
        try {
            requireConnected();

            long currentVersion = versions.get(point.name());
            if (request.expectedVersion() != null
                    && request.expectedVersion() != currentVersion) {
                Number currentValue = readFromChannel(point);
                log.info("[{}] STALE write rejected: point={}, expectedVersion={}, actualVersion={}",
                        deviceId, point.name(), request.expectedVersion(), currentVersion);
                return WriteOutcome.rejected(currentVersion, currentValue);
            }

            // 值合法性在 writeToChannel -> encode 内校验，越界直接抛 VALUE_OUT_OF_RANGE
            writeToChannel(point, request.value());

            long newVersion = currentVersion + 1;
            versions.put(point.name(), newVersion);
            commitSeq++;
            log.info("[{}] write accepted: point={}, value={}, version {} -> {}, commitSeq={}",
                    deviceId, point.name(), request.value(), currentVersion, newVersion, commitSeq);
            notifyCommitted();
            return WriteOutcome.accepted(newVersion);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 原子批量写入：一批点位作为一次逻辑提交。
     * <p>
     * 先在写锁内解析点位并做版本预检，任一点位过期即整批拒绝（不触碰通道、状态不变）；
     * 全部通过后编码、写通道，最后统一推进各点位版本与唯一一次 commitSeq。
     * 因而一致性快照永远观察不到“这批点位一半新一半旧”。
     */
    public BatchWriteResult writeBatch(List<WriteRequest> requests) {
        if (requests.isEmpty()) {
            throw new GatewayException(ErrorCategory.VALUE_OUT_OF_RANGE,
                    "writeBatch requires at least one request on " + deviceId);
        }
        lock.writeLock().lock();
        try {
            requireConnected();

            List<PointDefinition> targets = new ArrayList<>(requests.size());
            List<Long> currentVersions = new ArrayList<>(requests.size());
            for (WriteRequest request : requests) {
                PointDefinition point = findPoint(request.name());
                long currentVersion = versions.get(point.name());
                if (request.expectedVersion() != null
                        && request.expectedVersion() != currentVersion) {
                    List<WriteOutcome> rejected = new ArrayList<>();
                    for (PointDefinition t : targets) {
                        rejected.add(WriteOutcome.rejected(versions.get(t.name()),
                                readFromChannel(t)));
                    }
                    log.info("[{}] batch STALE rejected at point={}, expected={}, actual={}",
                            deviceId, point.name(), request.expectedVersion(), currentVersion);
                    return new BatchWriteResult(false, commitSeq, List.copyOf(rejected),
                            "stale version at point " + point.name() + ": expected "
                                    + request.expectedVersion() + " but actual " + currentVersion);
                }
                targets.add(point);
                currentVersions.add(currentVersion);
            }

            // 编码（越界校验）与通道写入全部完成后才提交版本
            for (int i = 0; i < requests.size(); i++) {
                writeToChannel(targets.get(i), requests.get(i).value());
            }
            List<WriteOutcome> outcomes = new ArrayList<>(requests.size());
            for (int i = 0; i < targets.size(); i++) {
                long newVersion = currentVersions.get(i) + 1;
                versions.put(targets.get(i).name(), newVersion);
                outcomes.add(WriteOutcome.accepted(newVersion));
            }
            commitSeq++;
            log.info("[{}] batch write accepted: points={}, commitSeq={}",
                    deviceId, targets.stream().map(PointDefinition::name).toList(), commitSeq);
            notifyCommitted();
            return new BatchWriteResult(true, commitSeq, List.copyOf(outcomes),
                    "batch of " + requests.size() + " point(s) committed atomically");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 内部：连接检查 / 通道读写 / 类型编解码 / 异常归一化
    // ------------------------------------------------------------------

    private void requireConnected() {
        if (!connected) {
            throw new GatewayException(ErrorCategory.DEVICE_DISCONNECTED,
                    "device " + deviceId + " is disconnected");
        }
    }

    private void notifyCommitted() {
        CommitListener listener = commitListener;
        if (listener != null) {
            listener.onCommitted(commitSeq);
        }
    }

    private Number readFromChannel(PointDefinition point) {
        try {
            return switch (point.type()) {
                case UINT16 -> channel.readRegisters(point.address(), 1)[0];
                case INT32, FLOAT32 -> {
                    int[] regs = channel.readRegisters(point.address(), 2);
                    yield decode(point.type(), regs);
                }
                case BOOL -> channel.readCoil(point.address()) ? 1 : 0;
            };
        } catch (SimulatedChannelException e) {
            throw normalize(e, point.address());
        }
    }

    private void writeToChannel(PointDefinition point, Number value) {
        try {
            switch (point.type()) {
                case UINT16 -> channel.writeRegisters(point.address(),
                        new int[]{encodeU16(value, point.address())});
                case INT32, FLOAT32 -> channel.writeRegisters(point.address(), encode(point, value));
                case BOOL -> channel.writeCoil(point.address(), encodeBool(value, point.address()));
            }
        } catch (SimulatedChannelException e) {
            throw normalize(e, point.address());
        }
    }

    private int[] encode(PointDefinition point, Number value) {
        return switch (point.type()) {
            case UINT16 -> new int[]{encodeU16(value, point.address())};
            case INT32 -> {
                long v = value.longValue();
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw outOfRange(point, value);
                }
                int bits = (int) v;
                yield new int[]{bits >>> 16, bits & 0xFFFF};
            }
            case FLOAT32 -> {
                float f = value.floatValue();
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw outOfRange(point, value);
                }
                int bits = Float.floatToRawIntBits(f);
                yield new int[]{bits >>> 16, bits & 0xFFFF};
            }
            case BOOL -> new int[]{encodeBool(value, point.address()) ? 1 : 0};
        };
    }

    private Number decode(PointType type, int[] regs) {
        int bits = (regs[0] << 16) | (regs[1] & 0xFFFF);
        return switch (type) {
            case INT32 -> bits;
            case FLOAT32 -> Float.intBitsToFloat(bits);
            default -> throw new IllegalStateException("unexpected type " + type);
        };
    }

    private int encodeU16(Number value, int address) {
        long v = value.longValue();
        if (v < 0L || v > 0xFFFFL) {
            throw new GatewayException(ErrorCategory.VALUE_OUT_OF_RANGE,
                    "value " + value + " out of UINT16 range [0,65535] at address " + address);
        }
        return (int) v;
    }

    private boolean encodeBool(Number value, int address) {
        int v = value.intValue();
        if (v != 0 && v != 1) {
            throw new GatewayException(ErrorCategory.VALUE_OUT_OF_RANGE,
                    "value " + value + " out of BOOL range {0,1} at address " + address);
        }
        return v == 1;
    }

    private GatewayException outOfRange(PointDefinition point, Number value) {
        return new GatewayException(ErrorCategory.VALUE_OUT_OF_RANGE,
                "value " + value + " not representable as " + point.type()
                        + " at point " + point.name() + " (address=" + point.address() + ")");
    }

    /**
     * 通道异常归一化：原始异常类型不出本层，仅保留为 cause 供日志/排查，
     * 对外返回明确分类的 {@link GatewayException}。
     */
    private GatewayException normalize(SimulatedChannelException e, int address) {
        ErrorCategory category;
        String message;
        if (e instanceof SimulatedChannelTimeoutException) {
            category = ErrorCategory.IO_TIMEOUT;
            message = "io timeout accessing address " + address + " on device " + deviceId;
        } else if (e instanceof InvalidChannelAddressException) {
            category = ErrorCategory.INVALID_ADDRESS;
            message = "invalid address " + address + " on device " + deviceId;
        } else if (e instanceof ValueOutOfBoundsException) {
            category = ErrorCategory.VALUE_OUT_OF_RANGE;
            message = "value out of bounds at address " + address + " on device " + deviceId;
        } else if (e instanceof ChannelClosedException) {
            category = ErrorCategory.DEVICE_DISCONNECTED;
            message = "device " + deviceId + " channel is closed";
        } else {
            category = ErrorCategory.INTERNAL_ERROR;
            message = "channel error at address " + address + " on device " + deviceId;
        }
        log.warn("[{}] normalized channel fault -> {} (raw={}, message={})",
                deviceId, category, e.getClass().getSimpleName(), e.getMessage());
        return new GatewayException(category, message, e);
    }
}
