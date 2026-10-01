package com.github.h3yow.industrialtelemetrygatewayjava.model;

import java.util.List;

/**
 * 一轮批量读取 / 周期采集的一致性快照。
 * <p>
 * 快照中所有点位均来自同一采样时刻（同一个已提交序号 commitSeq），
 * 不会出现一半新值一半旧值。
 *
 * @param deviceId  设备 id
 * @param timestamp 采样时刻（System.nanoTime 不可用于墙钟，此为 epochMillis）
 * @param commitSeq 采样时设备已提交写入的全局序号（单调递增）
 * @param samples   点位采样列表（按地址升序）
 */
public record Snapshot(String deviceId, long timestamp, long commitSeq, List<Sample> samples) {
}
