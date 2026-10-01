package com.github.h3yow.industrialtelemetrygatewayjava.model;

import java.util.List;

/**
 * 原子批量写结果：一批点位作为一次逻辑提交。
 * <p>
 * 要么整批提交（所有点位同时进入新版本、共享同一个 commitSeq 增量），
 * 要么整批拒绝（任何一个点位版本过期/非法即全部不生效）。
 *
 * @param accepted   是否整批接受
 * @param commitSeq  接受后设备全局提交序号
 * @param outcomes   每个点位的仲裁结果（按请求顺序）
 * @param reason     可读的判定原因
 */
public record BatchWriteResult(boolean accepted, long commitSeq,
                               List<WriteOutcome> outcomes, String reason) {
}
