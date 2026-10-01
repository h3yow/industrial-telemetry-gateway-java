package com.github.h3yow.industrialtelemetrygatewayjava.gateway;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * 周期性快照采集器：为每台设备按固定间隔产出一致性快照。
 * <p>
 * 每一轮对每台设备至多采样一次；设备断连时该帧不产出（监听器收到失败回调），
 * 采集线程不被阻塞（读取快速失败）。
 */
public class PeriodicCollector {

    public PeriodicCollector(ScheduledExecutorService scheduler) {
        // 骨架占位
    }

    /** 订阅一台设备的周期采集；listener 收到成功帧，errorListener 收到归一化异常。 */
    public void subscribe(SimulatedDevice device, long intervalMillis,
                          Consumer<CollectedSnapshot> listener,
                          Consumer<Throwable> errorListener) {
        // 骨架占位
    }

    /** 停止并取消全部采集任务。 */
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    public boolean isShutdown() {
        return false;
    }
}
