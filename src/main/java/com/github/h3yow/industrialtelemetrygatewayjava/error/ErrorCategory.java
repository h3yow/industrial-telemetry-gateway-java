package com.github.h3yow.industrialtelemetrygatewayjava.error;

/**
 * 网关对外暴露的归一化错误分类。
 * <p>
 * 底层模拟通道抛出的原始异常（超时/非法地址/越界/通道关闭等）
 * 不会原样透出，统一映射为本分类之一。
 */
public enum ErrorCategory {
    /** 设备不存在 */
    DEVICE_NOT_FOUND,
    /** 设备不可用（断连/通道关闭），读取快速失败 */
    DEVICE_DISCONNECTED,
    /** 点位地址非法 */
    INVALID_ADDRESS,
    /** 值越界或类型无法转换 */
    VALUE_OUT_OF_RANGE,
    /** 基于过期版本的写入被乐观锁拒绝 */
    STALE_VERSION,
    /** 底层 IO 超时 */
    IO_TIMEOUT,
    /** 其他未分类的服务端错误 */
    INTERNAL_ERROR
}
