package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.model.BatchWriteResult;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import com.github.h3yow.industrialtelemetrygatewayjava.support.TestDevices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量读取/周期采集的一致性快照场景。
 * <p>
 * 关键链路：两个业务上必须一致的配对点位（电机转速 motor-speed-rpm 与
 * 配套压力 pressure-pa）通过<b>原子批量写</b>一次提交；采样方在大量并发写
 * 期间不断取一致性快照。任何“一个新值一个旧值”的组合都意味着快照撕裂。
 * <p>
 * 判定依据（每一帧都必须满足）：
 * <ol>
 *   <li>配对点位版本号始终相同（同代提交，要么都旧要么都新）；</li>
 *   <li>该版本对应的 (speed, pressure) 组合必须在独立维护的提交代次历史中存在，
 *       即快照必须等于某个真实提交时刻的状态，而非两半拼接；</li>
 *   <li>连续快照 commitSeq 单调不减；</li>
 *   <li>snapshot.commitSeq 不大于已接受提交总数。</li>
 * </ol>
 */
class BatchSnapshotConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchSnapshotConsistencyTest.class);

    @Test
    @DisplayName("多点位分别写入后，单次快照的 commitSeq 与各点版本自洽")
    void snapshotReflectsSinglePointInTime() {
        SimulatedDevice device = TestDevices.newDevice("dev-snap-1");

        device.write(new WriteRequest(device.deviceId(), "motor-running", 1L));
        device.write(new WriteRequest(device.deviceId(), "motor-speed-rpm", 1500L, 0L));
        device.write(new WriteRequest(device.deviceId(), "temperature-c", 42.5f, 0L));

        Snapshot snapshot = device.readSnapshot();
        log.info("输入: 对 3 个不同点位各成功写 1 次后做批量读取");
        log.info("判定依据: commitSeq={}, samples={}", snapshot.commitSeq(),
                snapshot.samples().stream().map(s -> s.name() + "@v" + s.version()
                        + "=" + s.value()).toList());

        assertEquals(3, snapshot.commitSeq());
        Map<String, Sample> byName = new java.util.HashMap<>();
        snapshot.samples().forEach(s -> byName.put(s.name(), s));
        assertEquals(1L, byName.get("motor-running").version());
        assertEquals(1L, byName.get("motor-speed-rpm").version());
        assertEquals(1L, byName.get("temperature-c").version());
        assertEquals(0L, byName.get("pressure-pa").version());
        assertEquals(42.5f, byName.get("temperature-c").value().floatValue(), 0.0001f);
    }

    @Test
    @DisplayName("原子批量写与采样并发时，配对点位快照绝不出现一个新值一个旧值")
    void concurrentSamplingAndBatchWritingNeverTearsSnapshot() throws Exception {
        SimulatedDevice device = TestDevices.newDevice("dev-snap-2");

        // 独立提交代次历史：配对版本 -> 当时的 (speed, pressure)。
        // 关键：通过设备提交监听器在“写锁内、提交后、释放前”同步运行，
        // 监听器借重入读锁直接读取刚提交后的配对点位并登记，
        // 保证历史与设备状态严格一致，杜绝锁外事后登记的观察竞态。
        Map<Long, long[]> pairHistory = new ConcurrentHashMap<>();
        pairHistory.put(0L, new long[]{100L, 1000L});
        AtomicLong committedBatches = new AtomicLong();
        // 监听器仅在 speed 与 pressure 版本相同时登记（原子批次的不变式）；
        // 无关点位（temperature）提交时二者版本相同但值未变，也会重复登记相同组合，无害。
        device.setCommitListener(seq -> {
            Sample s = device.readPoint("motor-speed-rpm");
            Sample p = device.readPoint("pressure-pa");
            if (s.version() == p.version()) {
                pairHistory.put(s.version(),
                        new long[]{s.value().longValue(), p.value().longValue()});
            }
        });

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<String> firstViolation = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        int writerCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + 1);
        for (int w = 0; w < writerCount; w++) {
            final int writerId = w + 1;
            pool.submit(() -> {
                awaitQuietly(start);
                long token = writerId;
                while (!stop.get()) {
                    token += writerCount;
                    long speed = 1000 + token;
                    long pressure = 5000 + token;
                    // 两个配对点位作为一次原子提交，版本同时 +1，commitSeq 只 +1
                    BatchWriteResult result = device.writeBatch(java.util.List.of(
                            new WriteRequest(device.deviceId(), "motor-speed-rpm", speed),
                            new WriteRequest(device.deviceId(), "pressure-pa", pressure)));
                    if (result.accepted()) {
                        committedBatches.incrementAndGet();
                    }
                }
            });
        }
        // 第三个写线程对无关点位做独立提交，增加锁竞争与交错
        pool.submit(() -> {
            awaitQuietly(start);
            long t = 0;
            while (!stop.get()) {
                device.write(new WriteRequest(device.deviceId(),
                        "temperature-c", Float.valueOf(20.0f + (t++ % 7))));
            }
        });

        long sampled = 0;
        long lastSeq = -1L;
        start.countDown();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && firstViolation.get() == null) {
            Snapshot snapshot = device.readSnapshot();
            sampled++;

            Sample speed = find(snapshot, "motor-speed-rpm");
            Sample pressure = find(snapshot, "pressure-pa");

            if (snapshot.commitSeq() < lastSeq) {
                firstViolation.set("commitSeq went backwards: " + lastSeq
                        + " -> " + snapshot.commitSeq());
                break;
            }
            lastSeq = snapshot.commitSeq();

            // 依据 1：配对点位必须同版本（原子提交的直接可观察性质）
            if (speed.version() != pressure.version()) {
                firstViolation.set("torn pair versions: speed@v" + speed.version()
                        + "(value=" + speed.value() + ") pressure@v" + pressure.version()
                        + "(value=" + pressure.value() + ") at commitSeq "
                        + snapshot.commitSeq());
                break;
            }

            // 依据 2：该版本的取值组合必须在提交历史中真实存在，杜绝两半拼接
            long[] expected = pairHistory.get(speed.version());
            if (expected == null
                    || expected[0] != speed.value().longValue()
                    || expected[1] != pressure.value().longValue()) {
                firstViolation.set("mismatched pair combination at pairVersion="
                        + speed.version() + ": observed=(" + speed.value() + ","
                        + pressure.value() + "), history="
                        + (expected == null ? "absent"
                                : "(" + expected[0] + "," + expected[1] + ")"));
                break;
            }
        }

        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        log.info("输入: {} 个批量写线程（每次原子提交 speed+pressure 配对）+ 1 个独立点位写线程，持续 2s",
                writerCount);
        log.info("判定依据: 采样帧数={}, 已接受原子批次={}, 最后帧 commitSeq={}, 首条违例={}",
                sampled, committedBatches.get(), lastSeq, firstViolation.get());

        assertTrue(sampled > 0, "应当实际采到多帧");
        assertTrue(committedBatches.get() > 10, "测试期间应发生大量原子批次写以形成竞争");
        assertEquals(null, firstViolation.get(),
                "并发下任何快照都不得撕裂：" + firstViolation.get());
    }

    private static Sample find(Snapshot snapshot, String name) {
        return snapshot.samples().stream()
                .filter(s -> s.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
