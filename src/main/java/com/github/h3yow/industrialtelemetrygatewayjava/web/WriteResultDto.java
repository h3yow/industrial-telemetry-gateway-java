package com.github.h3yow.industrialtelemetrygatewayjava.web;

/**
 * 写入结果（仲裁结论与判定依据）。
 */
public record WriteResultDto(boolean accepted,
                             long committedVersion,
                             long actualVersion,
                             Object currentValue,
                             String reason) {
}
