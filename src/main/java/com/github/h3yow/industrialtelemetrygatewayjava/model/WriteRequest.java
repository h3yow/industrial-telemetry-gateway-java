package com.github.h3yow.industrialtelemetrygatewayjava.model;

/**
 * 点位写入请求。
 *
 * @param deviceId        目标设备
 * @param name            点位名
 * @param value           待写入数值
 * @param expectedVersion 客户端基于的版本（乐观锁）；为 null 表示无条件写入
 */
public record WriteRequest(String deviceId, String name, Number value, Long expectedVersion) {

    public WriteRequest(String deviceId, String name, Number value) {
        this(deviceId, name, value, null);
    }
}
