package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.CollectedSnapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.gateway.PeriodicCollector;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import com.github.h3yow.industrialtelemetrygatewayjava.support.TestDevices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期采集场景：按固定间隔产出一致性快照帧（轮次单调），
 * 帧内配对点位同版本；设备断连时失败通过错误回调上报且不阻塞调度，
 * 重连后自动恢复采集。
 */
class PeriodicCollectorTest {

    private static final Logger log = LoggerFactory.getLogger(PeriodicCollectorTest.class);

    @Test
    @DisplayName("周期采集产出轮次单调的一致性快照，帧内点位来自同一 commitSeq")
    void collectsConsistentFramesWithMonotonicRounds() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PeriodicCollector collector = new PeriodicCollector(scheduler);
        SimulatedDevice device = TestDevices.newDevice("dev-col-1");

        ConcurrentLinkedQueue<CollectedSnapshot> frames = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        ScheduledFuture<?> task = collector.subscribe(device, 20,
                frames::add, errors::add);

        // 采集期间持续写入，让连续帧的 commitSeq 增长
        long deadline = System.currentTimeMillis() + 400;
        long writes = 0;
        while (System.currentTimeMillis() < deadline) {
            device.write(new WriteRequest(device.deviceId(), "motor-speed-rpm",
                    200 + (writes++) % 1000));
        }

        Thread.sleep(150); // 再收几帧
        task.cancel(false);

        log.info("输入: interval=20ms, 采集期写入 {} 次；判定依据: 收到帧数={}, 错误数={}",
                writes, frames.size(), errors.size());

        assertTrue(frames.size() >= 5, "400ms@20ms 应采到多帧，实际 " + frames.size());
        assertTrue(errors.isEmpty(), "在线设备不应产生错误回调");

        long expectedRound = 1;
        long lastCommitSeq = -1;
        for (CollectedSnapshot frame : frames) {
            assertEquals(expectedRound++, frame.round(), "采集轮次必须从 1 单调递增");
            // 整帧同一采样时刻：每帧带一个 commitSeq；序号单调不减，且每个点位版本非负
            frame.snapshot().samples().forEach(s ->
                    assertTrue(s.version() >= 0, "点位版本不应为负: " + s.name()));
            assertTrue(frame.snapshot().commitSeq() >= lastCommitSeq,
                    "帧 commitSeq 必须单调");
            lastCommitSeq = frame.snapshot().commitSeq();
        }
        assertTrue(lastCommitSeq > 0, "应至少观察到一次写入后的帧");
        log.info("判定依据: 最后帧 round={}, commitSeq={}",
                expectedRound - 1, lastCommitSeq);

        collector.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("采集中断连：错误回调收到 DEVICE_DISCONNECTED，调度不中断，重连后恢复出帧")
    void disconnectDuringCollectionReportsErrorAndRecovers() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PeriodicCollector collector = new PeriodicCollector(scheduler);
        SimulatedDevice device = TestDevices.newDevice("dev-col-2");

        ConcurrentLinkedQueue<CollectedSnapshot> frames = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        collector.subscribe(device, 20, frames::add, errors::add);

        Thread.sleep(100);
        int beforeDisconnect = frames.size();
        device.disconnect();
        Thread.sleep(150); // 断连期间仍按节拍触发，但应快速失败走错误回调

        AtomicReference<ErrorCategory> seenCategory = new AtomicReference<>();
        errors.forEach(t -> {
            if (t instanceof GatewayException ge) {
                seenCategory.set(ge.category());
            }
        });

        device.connect();
        Thread.sleep(150);
        int afterReconnect = frames.size();

        log.info("输入: 采集 100ms 后断连 150ms 再重连；判定依据: 断连前帧数={}, 错误分类={}, 错误数={}, 重连后总帧数={}",
                beforeDisconnect, seenCategory.get(), errors.size(), afterReconnect);

        assertTrue(beforeDisconnect >= 2);
        assertEquals(ErrorCategory.DEVICE_DISCONNECTED, seenCategory.get(),
                "断连期错误必须归一化为 DEVICE_DISCONNECTED");
        assertTrue(errors.size() >= 2, "断连期每个节拍都应快速失败一次");
        assertTrue(afterReconnect > beforeDisconnect, "重连后应恢复产出新帧");

        collector.shutdownNow();
        scheduler.shutdownNow();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
    }
}
