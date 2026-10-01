package com.github.h3yow.industrialtelemetrygatewayjava.service;

/**
 * 对外暴露的统一错误码。底层通道异常被归一化为这些类别，
 * 调用方可以据此区分超时、非法地址、越界、断连与版本冲突。
 */
public enum GatewayErrorCode {
    /** 底层通道读写超时 */
    CHANNEL_TIMEOUT,
    /** 点位地址非法 */
    INVALID_ADDRESS,
    /** 写入值越界 */
    VALUE_OUT_OF_RANGE,
    /** 通道已关闭 / 设备断连，快速失败 */
    DEVICE_UNAVAILABLE,
    /** 基于过期版本的写入被拒绝 */
    STALE_VERSION,
    /** 点位不存在 */
    POINT_NOT_FOUND
}
