package com.github.h3yow.industrialtelemetrygatewayjava.model;

/**
 * 写入仲裁结果。
 * <p>
 * accepted=true 时 committedVersion 为写入后点位的新版本；
 * accepted=false 时 actualVersion 为当前实际版本，currentValue 为当前实际值。
 *
 * @param accepted         是否被接受
 * @param committedVersion 接受时的新版本
 * @param actualVersion    被拒时当前版本
 * @param currentValue     被拒时当前值
 */
public record WriteOutcome(boolean accepted, long committedVersion, long actualVersion, Number currentValue) {

    public static WriteOutcome accepted(long committedVersion) {
        return new WriteOutcome(true, committedVersion, committedVersion, null);
    }

    public static WriteOutcome rejected(long actualVersion, Number currentValue) {
        return new WriteOutcome(false, -1L, actualVersion, currentValue);
    }
}
