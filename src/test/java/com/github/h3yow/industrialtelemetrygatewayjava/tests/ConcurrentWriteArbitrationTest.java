package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import com.github.h3yow.industrialtelemetrygatewayjava.support.TestDevices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发写入仲裁场景：
 * 同一点位上多客户端并发、且基于同一旧版本写入时，恰好一个成功、其余被拒，
 * 最终值/版本确定；无条件写入串行化后无一丢失且版本连续。
 */
class ConcurrentWriteArbitrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWriteArbitrationTest.class);

    @Test
    @DisplayName("同一版本并发 CAS 写入：恰好一个被接受，其余全部过期拒绝，最终状态确定")
    void concurrentCasWritesSameBaseVersionExactlyOneWins() throws Exception {
        SimulatedDevice device = TestDevices.newDevice("dev-conc-1");
        int threads = 20;

        Sample before = device.readPoint("motor-speed-rpm");
        log.info("输入: threads={}, point={}, baseValue={}, baseVersion={}",
                threads, "motor-speed-rpm", before.value(), before.version());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Map<Integer, WriteOutcome> outcomes = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= threads; i++) {
            int writerId = i;
            long expectedVersion = before.version();
            futures.add(pool.submit(() -> {
                await(barrier); // 最大化竞争：所有线程同时进入
                WriteOutcome outcome = device.write(
                        new WriteRequest(device.deviceId(), "motor-speed-rpm",
                                writerId * 10L, expectedVersion));
                outcomes.put(writerId, outcome);
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        long accepted = outcomes.values().stream().filter(WriteOutcome::accepted).count();
        long rejected = outcomes.values().stream().filter(o -> !o.accepted()).count();
        Integer winner = outcomes.entrySet().stream()
                .filter(e -> e.getValue().accepted())
                .map(Map.Entry::getKey).findFirst().orElse(null);

        Sample after = device.readPoint("motor-speed-rpm");

        log.info("判定依据: accepted={}, rejected={}, winner={}, finalValue={}, finalVersion={}",
                accepted, rejected, winner, after.value(), after.version());
        outcomes.forEach((id, o) ->
                log.info("  writer={} -> accepted={}, committedVersion={}, actualVersion={}",
                        id, o.accepted(), o.committedVersion(), o.actualVersion()));

        assertEquals(1, accepted, "并发同版本 CAS 必须恰好一个成功");
        assertEquals(threads - 1, rejected, "其余必须全部因过期版本被拒");
        assertNotNull(winner);
        assertEquals(winner * 10L, after.value().longValue(),
                "最终值必须等于唯一获胜者写入的值");
        assertEquals(1, after.version(), "最终版本必须为 1，绝不被静默覆盖多次");
    }

    @Test
    @DisplayName("无条件并发写入：全部串行提交，版本连续 1..N，无丢失")
    void unconditionalConcurrentWritesAllCommittedSequentially() throws Exception {
        SimulatedDevice device = TestDevices.newDevice("dev-conc-2");
        int threads = 32;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<WriteOutcome>> futures = new ArrayList<>();

        for (int i = 1; i <= threads; i++) {
            int v = i;
            futures.add(pool.submit(() -> {
                await(barrier);
                return device.write(new WriteRequest(device.deviceId(),
                        "motor-speed-rpm", (long) v));
            }));
        }

        List<Long> committedVersions = new ArrayList<>();
        for (Future<WriteOutcome> f : futures) {
            WriteOutcome outcome = f.get(10, TimeUnit.SECONDS);
            assertTrue(outcome.accepted());
            committedVersions.add(outcome.committedVersion());
        }
        pool.shutdownNow();

        Sample after = device.readPoint("motor-speed-rpm");
        log.info("输入: threads={} 无条件并发写同一 UINT16 点位", threads);
        log.info("判定依据: 提交版本集合={}, finalVersion={}, finalValue={}",
                committedVersions.stream().sorted().toList(),
                after.version(), after.value());

        assertEquals(threads, committedVersions.size());
        assertEquals((long) threads, after.version(), "每次写入都提交，版本连续到 N");
        assertTrue(committedVersions.containsAll(range(1, threads)),
                "提交版本必须恰好覆盖 1..N，无重复无丢失");
        assertEquals(threads, committedVersions.stream().distinct().count(),
                "不允许两个写入拿到同一版本");
        assertTrue(after.value().longValue() >= 1 && after.value().longValue() <= threads);
    }

    private static List<Long> range(int from, int to) {
        List<Long> list = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
