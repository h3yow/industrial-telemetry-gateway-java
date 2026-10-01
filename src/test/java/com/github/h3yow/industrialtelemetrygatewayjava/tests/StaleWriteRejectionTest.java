package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.model.Sample;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteOutcome;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import com.github.h3yow.industrialtelemetrygatewayjava.support.TestDevices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 过期写入拒绝 + 非法地址 + 越界值的区分场景。
 * 三类错误必须走不同出口：过期版本是“拒绝结果”，地址与越界是“归一化异常分类”。
 */
class StaleWriteRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(StaleWriteRejectionTest.class);

    @Test
    @DisplayName("基于过期版本的写入被明确拒绝，且不改变当前值与版本")
    void staleWriteRejectedWithoutOverwriting() {
        SimulatedDevice device = TestDevices.newDevice("dev-stale-1");

        WriteOutcome first = device.write(
                new WriteRequest(device.deviceId(), "pressure-pa", 2000, 0L));
        Sample afterFirst = device.readPoint("pressure-pa");
        log.info("输入: 第一次写 value=2000 expectedVersion=0 -> accepted={}, committedVersion={}",
                first.accepted(), first.committedVersion());

        WriteOutcome stale = device.write(
                new WriteRequest(device.deviceId(), "pressure-pa", 9999, 0L));
        Sample afterStale = device.readPoint("pressure-pa");
        log.info("判定依据: 再以过期 expectedVersion=0 写 value=9999 -> accepted={}, actualVersion={}, currentValue={}",
                stale.accepted(), stale.actualVersion(), stale.currentValue());

        assertFalse(stale.accepted(), "过期版本写入必须被拒绝");
        assertEquals(1, stale.actualVersion(), "拒绝结果需告知当前实际版本");
        assertEquals(2000, stale.currentValue().intValue(), "拒绝结果需回传当前实际值");
        assertEquals(2000, afterStale.value().intValue(), "被拒后点位值不得被覆盖");
        assertEquals(1, afterStale.version(), "被拒后版本不得前进");
    }

    @Test
    @DisplayName("版本链上落后多个版本的写入同样被拒；携带最新版本的写入成功")
    void multiStepVersionChainOnlyLatestAccepted() {
        SimulatedDevice device = TestDevices.newDevice("dev-stale-2");
        device.write(new WriteRequest(device.deviceId(), "pressure-pa", 1001, 0L));
        device.write(new WriteRequest(device.deviceId(), "pressure-pa", 1002, 1L));
        Sample current = device.readPoint("pressure-pa");

        WriteOutcome stale = device.write(
                new WriteRequest(device.deviceId(), "pressure-pa", 8888, 1L));
        WriteOutcome fresh = device.write(
                new WriteRequest(device.deviceId(), "pressure-pa", 1003, 2L));

        log.info("输入: 当前 actualVersion={} value={}; 分别以 expectedVersion=1 和 2 写入",
                current.version(), current.value());
        log.info("判定依据: stale.accepted={}, actualVersion={}; fresh.accepted={}, committedVersion={}",
                stale.accepted(), stale.actualVersion(), fresh.accepted(), fresh.committedVersion());

        assertFalse(stale.accepted());
        assertEquals(2, stale.actualVersion());
        assertEquals(3, fresh.committedVersion(), "携带当前版本 2 的写入成功，新版本为 3");
    }

    @Test
    @DisplayName("非法地址/不存在点位 与 值越界 抛出不同分类的归一化异常")
    void invalidAddressAndOutOfRangeValueAreDistinguishable() {
        SimulatedDevice device = TestDevices.newDevice("dev-stale-3");

        GatewayException missing = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(), "ghost-point", 1, 0L)));
        GatewayException tooLarge = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(), "motor-speed-rpm", 70000L, 0L)));
        GatewayException negative = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(), "motor-speed-rpm", -1L, 0L)));
        GatewayException badBool = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(), "motor-running", 5L, 0L)));

        log.info("输入: 不存在点位 ghost-point / UINT16 写 70000 / UINT16 写 -1 / BOOL 写 5");
        log.info("判定依据: 分类分别为 {} / {} / {} / {}",
                missing.category(), tooLarge.category(), negative.category(), badBool.category());

        assertEquals(ErrorCategory.INVALID_ADDRESS, missing.category());
        assertEquals(ErrorCategory.VALUE_OUT_OF_RANGE, tooLarge.category());
        assertEquals(ErrorCategory.VALUE_OUT_OF_RANGE, negative.category());
        assertEquals(ErrorCategory.VALUE_OUT_OF_RANGE, badBool.category());

        Sample untouched = device.readPoint("motor-speed-rpm");
        assertEquals(100, untouched.value().intValue(), "越界写入不得改动点位");
        assertEquals(0, untouched.version(), "越界写入不得推进版本");
    }
}
