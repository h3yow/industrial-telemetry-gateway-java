package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedDeviceChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointStore;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayErrorCode;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.service.TelemetryGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发写入仲裁：多个客户端基于同一期望版本同时写同一点位，
 * 必须有且仅有一个成功，其余被明确拒绝（STALE_VERSION），最终状态确定。
 */
class ConcurrentWriteArbitrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWriteArbitrationTest.class);
    private static final int ADDRESS = 7;
    private static final int WRITERS = 16;

    private TelemetryGatewayService service;

    @BeforeEach
    void setUp() {
        SimulatedDeviceChannel channel = new SimulatedDeviceChannel();
        channel.setIoLatencyMillis(0);
        service = new TelemetryGatewayService(channel, new PointStore());
        service.acquireOnce();
    }

    @Test
    void concurrentWritesWithSameVersion_exactlyOneWins() throws Exception {
        long baseVersion = service.readPoint(ADDRESS).version();
        log.info("输入: address={}, 并发写入者数={}, 共同期望版本={}", ADDRESS, WRITERS, baseVersion);

        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(WRITERS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger staleRejected = new AtomicInteger();
        List<Double> acceptedValues = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            for (int i = 0; i < WRITERS; i++) {
                double value = 1000 + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        service.writePoint(ADDRESS, value, baseVersion);
                        success.incrementAndGet();
                        acceptedValues.add(value);
                    } catch (GatewayException e) {
                        if (e.code() == GatewayErrorCode.STALE_VERSION) {
                            staleRejected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        PointValue finalValue = service.readPoint(ADDRESS);
        log.info("判定依据: 成功数={}(应=1), 过期拒绝数={}(应={}), 最终值={}, 最终版本={}(应>{})",
                success.get(), staleRejected.get(), WRITERS - 1, finalValue.value(), finalValue.version(), baseVersion);

        assertEquals(1, success.get(), "同一期望版本下只能有一个写入成功");
        assertEquals(WRITERS - 1, staleRejected.get(), "其余写入必须被 STALE_VERSION 明确拒绝");
        assertEquals(acceptedValues.get(0), finalValue.value(), "最终状态必须等于唯一成功者的写入值");
        assertTrue(finalValue.version() > baseVersion, "成功写入后版本必须前进");
    }

    @Test
    void concurrentReadModifyWrite_everySuccessAdvancesVersionExactlyOnce() throws Exception {
        int writers = 8;
        int attemptsPerWriter = 50;
        log.info("输入: address={}, 写入者数={}, 每人尝试 read-modify-write {} 次", ADDRESS, writers, attemptsPerWriter);

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger staleRejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        for (int attempt = 0; attempt < attemptsPerWriter; attempt++) {
                            PointValue current = service.readPoint(ADDRESS);
                            try {
                                service.writePoint(ADDRESS, current.value() + 1, current.version());
                                success.incrementAndGet();
                            } catch (GatewayException e) {
                                if (e.code() == GatewayErrorCode.STALE_VERSION) {
                                    staleRejected.incrementAndGet();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        PointValue finalValue = service.readPoint(ADDRESS);
        long versionBefore = 1; // 首轮采集分配的版本号
        log.info("判定依据: 成功写入数={}, 过期拒绝数={}, 最终值={}(应=成功数), 版本前进量={}(应=成功数)",
                success.get(), staleRejected.get(), finalValue.value(), finalValue.version() - versionBefore);

        assertEquals(success.get(), (int) finalValue.value(),
                "每次成功的 +1 写入都必须生效，最终值等于成功次数（无丢失更新）");
        assertEquals(success.get(), finalValue.version() - versionBefore,
                "每次成功写入版本恰好前进 1，无静默覆盖");
        assertTrue(staleRejected.get() > 0, "并发下必然出现过期写入，且被明确拒绝");
    }
}
