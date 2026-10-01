package com.github.h3yow.industrialtelemetrygatewayjava.device;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.model.PointDefinition;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;

import java.util.List;

/**
 * 进程内模拟设备：点位视图 + 乐观锁版本 + 一致性快照 + 通道异常归一化。
 * <p>
 * 并发策略：单点状态由读写锁保护；批量读取在一次读锁内完成全量采样，
 * 与写入互斥，从而保证快照中所有点位来自同一采样时刻；
 * 每次成功写入推进单调的全局 commitSeq。
 */
public class SimulatedDevice {

    public SimulatedDevice(String deviceId, SimulatedChannel channel, List<PointDefinition> points) {
        // 骨架占位
    }

    public String deviceId() {
        return null;
    }

    public boolean isConnected() {
        return false;
    }

    /** 建立连接（打开通道）。 */
    public void connect() {
        // 骨架占位
    }

    /** 断开连接（关闭通道）；之后读写快速失败。 */
    public void disconnect() {
        // 骨架占位
    }

    public PointDefinition findPoint(String name) {
        return null;
    }

    /** 单点读取（读锁内采样）。 */
    public Sample readPoint(String name) {
        return null;
    }

    /** 一致性批量读取：同一读锁内完成，所有点位同属一个 commitSeq。 */
    public Snapshot readSnapshot() {
        return null;
    }

    /**
     * 带乐观锁的点位写入仲裁。
     * <p>
     * expectedVersion 非 null 且与当前版本不一致时拒绝（过期写入）；
     * 并发 CAS 由写锁串行化，最终状态确定。
     */
    public WriteOutcome write(WriteRequest request) {
        return null;
    }
}
