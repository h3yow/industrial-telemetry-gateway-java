package com.github.h3yow.industrialtelemetrygatewayjava.model;

/**
 * 单个点位在某次快照中的采样值，带该点位自身的版本号。
 *
 * @param name    点位名
 * @param address 地址
 * @param value   采样值
 * @param version 采样时该点位的版本（写入成功一次版本 +1）
 */
public record Sample(String name, int address, Number value, long version) {
}
