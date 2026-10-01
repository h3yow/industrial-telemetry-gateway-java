package com.github.h3yow.industrialtelemetrygatewayjava.model;

/**
 * 点位静态定义（地址、类型、初始值）。
 *
 * @param name         点位名（业务唯一键）
 * @param address      模拟通道上的寄存器地址（含保持寄存器与线圈）
 * @param type         数据类型
 * @param initialValue 初始值
 */
public record PointDefinition(String name, int address, PointType type, Number initialValue) {
}
