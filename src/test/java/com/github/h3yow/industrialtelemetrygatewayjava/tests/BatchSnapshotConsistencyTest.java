package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedDeviceChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointStore;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.service.TelemetryGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量读取一致性快照。
 *
 * <p>写入者每轮按地址顺序把 64 个点位依次写成同一个递增值，
 * 因此任何真实存在过的存储状态都呈"阶梯形"：前 k 个点位是新一轮的值，
 * 其余是上一轮的值，即快照值序列必须满足 非递增 且 最大值-最小值 ≤ 1。
 * 任何其他形态（如中间凹陷、跳变超过 1）都说明批量读取被写入撕裂。
 */
class BatchSnapshotConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchSnapshotConsistencyTest.class);
    private static final int POINT_COUNT = 64;
    private static final Set<Integer> ALL_ADDRESSES =
            IntStream.range(0, POINT_COUNT).boxed().collect(Collectors.toSet());

    private TelemetryGatewayService service;

    @BeforeEach
    void setUp() {
        SimulatedDeviceChannel channel = new SimulatedDeviceChannel();
        channel.setIoLatencyMillis(0);
        service = new TelemetryGatewayService(channel, new PointStore());
        service.acquireOnce();
    }

    @Test
    void batchSnapshotNeverTornWhileWriterSweepsAllPoints() throws Exception {
        int writeRounds = 20_000;
        log.info("输入: 点位数={}, 写入轮数={}, 每轮按地址顺序把所有点位写成同一递增值", POINT_COUNT, writeRounds);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong checks = new AtomicLong();
        AtomicLong tornReads = new AtomicLong();
        CountDownLatch writerDone = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    for (int round = 1; round <= writeRounds; round++) {
                        sweepWrite(round);
                        Thread.yield(); // 让出 CPU，保证读写真正交错
                    }
                } finally {
                    stop.set(true);
                    writerDone.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    while (!stop.get()) {
                        Map<Integer, PointValue> snapshot = service.readBatch(ALL_ADDRESSES);
                        checks.incrementAndGet();
                        if (!isStepShaped(snapshot)) {
                            tornReads.incrementAndGet();
                            log.error("撕裂快照: {}", new TreeSet<>(snapshot.entrySet()));
                        }
                    }
                } catch (Throwable t) {
                    log.error("读取者异常退出", t);
                }
            });

            assertTrue(writerDone.await(60, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        log.info("判定依据: 批量快照次数={}, 撕裂次数={}(应=0；真实状态必为阶梯形：非递增且极差≤1)",
                checks.get(), tornReads.get());
        assertTrue(checks.get() > 0, "读取者必须真的执行过批量读取");
        assertEquals(0, tornReads.get(), "批量快照不允许出现从未共存过的点位组合");
    }

    @Test
    void acquisitionCycleSharesOneSamplingInstant() {
        service.acquireOnce();
        Map<Integer, PointValue> all = service.readBatch(ALL_ADDRESSES);
        Set<Instant> instants = all.values().stream().map(PointValue::sampledAt).collect(Collectors.toSet());
        Set<Long> versions = all.values().stream().map(PointValue::version).collect(Collectors.toSet());
        log.info("判定依据: 一轮采集内采样时刻数={}(应=1), 采集版本数={}(应=1)", instants.size(), versions.size());
        assertEquals(1, instants.size(), "同一轮采集的点位必须来自同一采样时刻");
        assertEquals(1, versions.size(), "同一轮采集的点位必须共享同一采集版本");
    }

    /** 阶梯形判定：值序列非递增，且最大值与最小值之差不超过 1 */
    private boolean isStepShaped(Map<Integer, PointValue> snapshot) {
        List<Double> values = new ArrayList<>();
        for (int address = 0; address < POINT_COUNT; address++) {
            PointValue pv = snapshot.get(address);
            if (pv == null) {
                return false;
            }
            values.add(pv.value());
        }
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (max - min > 1) {
            return false;
        }
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    /** 按地址顺序把每个点位写成 value；与采集并发导致版本过期时重读重试 */
    private void sweepWrite(double value) {
        for (int address = 0; address < POINT_COUNT; address++) {
            while (true) {
                try {
                    PointValue current = service.readPoint(address);
                    service.writePoint(address, value, current.version());
                    break;
                } catch (GatewayException stale) {
                    // 版本过期，重读版本后重试
                }
            }
        }
    }
}
