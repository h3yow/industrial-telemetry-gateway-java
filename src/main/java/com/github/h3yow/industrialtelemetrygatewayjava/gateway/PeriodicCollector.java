package com.github.h3yow.industrialtelemetrygatewayjava.gateway;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 周期性快照采集器：为每台设备按固定间隔产出一致性快照。
 * <p>
 * 每一轮调用一次 {@link SimulatedDevice#readSnapshot()}，整帧点位同属一个 commitSeq；
 * 设备断连时读取快速失败，异常以归一化形式交给 errorListener，
 * 采集调度线程不会被阻塞。
 */
public class PeriodicCollector {

    private static final Logger log = LoggerFactory.getLogger(PeriodicCollector.class);

    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    private volatile boolean shutdown;

    public PeriodicCollector(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 订阅一台设备的周期采集。
     *
     * @param device         目标设备
     * @param intervalMillis 采集间隔（毫秒）
     * @param listener       成功帧回调
     * @param errorListener  归一化异常回调
     * @return 该订阅的任务句柄
     */
    public ScheduledFuture<?> subscribe(SimulatedDevice device, long intervalMillis,
                                        Consumer<CollectedSnapshot> listener,
                                        Consumer<Throwable> errorListener) {
        if (intervalMillis <= 0) {
            throw new GatewayException(ErrorCategory.VALUE_OUT_OF_RANGE,
                    "intervalMillis must be positive: " + intervalMillis);
        }
        AtomicLong round = new AtomicLong();
        Runnable job = () -> {
            long r = round.incrementAndGet();
            try {
                listener.accept(new CollectedSnapshot(r, device.readSnapshot()));
            } catch (Throwable t) {
                log.warn("[{}] collection round {} failed: {} - {}",
                        device.deviceId(), r,
                        t instanceof GatewayException ge ? ge.category() : ErrorCategory.INTERNAL_ERROR,
                        t.getMessage());
                errorListener.accept(t);
            }
        };
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                job, 0L, intervalMillis, TimeUnit.MILLISECONDS);
        tasks.add(future);
        return future;
    }

    /** 取消指定采集任务。 */
    public boolean unsubscribe(ScheduledFuture<?> future) {
        tasks.remove(future);
        return future.cancel(false);
    }

    /** 停止并取消全部采集任务。 */
    public List<Runnable> shutdownNow() {
        shutdown = true;
        for (ScheduledFuture<?> task : tasks) {
            task.cancel(false);
        }
        tasks.clear();
        return scheduler.shutdownNow();
    }

    public boolean isShutdown() {
        return shutdown;
    }
}
