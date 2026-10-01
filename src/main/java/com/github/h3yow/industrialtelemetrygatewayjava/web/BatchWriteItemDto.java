package com.github.h3yow.industrialtelemetrygatewayjava.web;

/**
 * 原子批量写中的单个点位项。
 */
public record BatchWriteItemDto(String name, Number value, Long expectedVersion) {
}
