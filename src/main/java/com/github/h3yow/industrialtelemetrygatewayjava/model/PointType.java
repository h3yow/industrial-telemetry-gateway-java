package com.github.h3yow.industrialtelemetrygatewayjava.model;

/**
 * 点位数据类型。
 */
public enum PointType {
    /** 16 位无符号整数，取值范围 [0, 65535] */
    UINT16,
    /** 32 位有符号整数，取值范围 [-2147483648, 2147483647] */
    INT32,
    /** 32 位单精度浮点 */
    FLOAT32,
    /** 布尔量，取值 0/1 */
    BOOL
}
