package com.github.h3yow.industrialtelemetrygatewayjava.core;

import java.time.Instant;

/**
 * 点位值：携带单调递增的版本号与采样时刻。
 * 版本号用于并发写入仲裁（CAS），采样时刻用于标识同一轮采集。
 */
public record PointValue(double value, long version, Instant sampledAt) {
}
