package com.github.h3yow.industrialtelemetrygatewayjava.web;

/**
 * 写入请求体。expectedVersion 缺省表示无条件写入。
 */
public record WriteRequestDto(Number value, Long expectedVersion) {
}
