package com.github.h3yow.industrialtelemetrygatewayjava.gateway;

import com.github.h3yow.industrialtelemetrygatewayjava.model.Snapshot;

/**
 * 周期采集器产出的一帧：带采集序号（从 1 起单调递增）。
 *
 * @param round    采集轮次
 * @param snapshot 一致性快照
 */
public record CollectedSnapshot(long round, Snapshot snapshot) {
}
