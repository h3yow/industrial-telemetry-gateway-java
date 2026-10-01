package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedDeviceChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointStore;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointValue;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayErrorCode;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.service.TelemetryGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 过期写入必须被明确拒绝（STALE_VERSION），而不是静默覆盖；
 * 且过期、非法地址、值越界三类错误的错误码必须可区分。
 */
class StaleWriteRejectedTest {

    private static final Logger log = LoggerFactory.getLogger(StaleWriteRejectedTest.class);
    private static final int ADDRESS = 3;

    private TelemetryGatewayService service;

    @BeforeEach
    void setUp() {
        SimulatedDeviceChannel channel = new SimulatedDeviceChannel();
        channel.setIoLatencyMillis(0);
        service = new TelemetryGatewayService(channel, new PointStore());
        service.acquireOnce();
    }

    @Test
    void staleWriteIsRejectedNotSilentlyOverwritten() {
        PointValue base = service.readPoint(ADDRESS);
        log.info("输入: address={}, 当前版本={}, 第一次写入 value=42.0 expectedVersion={}",
                ADDRESS, base.version(), base.version());

        PointValue written = service.writePoint(ADDRESS, 42.0, base.version());
        log.info("第一次写入成功: 新版本={}", written.version());

        log.info("输入: 用过期版本 {} 重放写入 value=999.0", base.version());
        GatewayException stale = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 999.0, base.version()));

        PointValue after = service.readPoint(ADDRESS);
        log.info("判定依据: 错误码={}(应=STALE_VERSION), 拒绝后点位值={}(应=42.0), 版本={}(应={})",
                stale.code(), after.value(), after.version(), written.version());

        assertEquals(GatewayErrorCode.STALE_VERSION, stale.code());
        assertEquals(42.0, after.value(), "过期写入不得静默覆盖当前值");
        assertEquals(written.version(), after.version(), "过期写入不得推进版本");
    }

    @Test
    void futureVersionIsAlsoRejected() {
        PointValue base = service.readPoint(ADDRESS);
        long futureVersion = base.version() + 100;
        log.info("输入: address={}, 当前版本={}, 以未来版本 {} 写入", ADDRESS, base.version(), futureVersion);

        GatewayException e = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 1.0, futureVersion));
        log.info("判定依据: 错误码={}(应=STALE_VERSION), message={}", e.code(), e.getMessage());
        assertEquals(GatewayErrorCode.STALE_VERSION, e.code());
    }

    @Test
    void invalidAddressOutOfRangeAndStaleAreDistinguishable() {
        PointValue base = service.readPoint(ADDRESS);

        GatewayException invalidAddress = assertThrows(GatewayException.class,
                () -> service.writePoint(9999, 1.0, base.version()));
        GatewayException outOfRange = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, Double.MAX_VALUE, base.version()));
        service.writePoint(ADDRESS, 5.0, base.version());
        GatewayException stale = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 6.0, base.version()));

        log.info("判定依据: 非法地址→{}(应=INVALID_ADDRESS), 值越界→{}(应=VALUE_OUT_OF_RANGE), 过期→{}(应=STALE_VERSION)",
                invalidAddress.code(), outOfRange.code(), stale.code());

        assertEquals(GatewayErrorCode.INVALID_ADDRESS, invalidAddress.code());
        assertEquals(GatewayErrorCode.VALUE_OUT_OF_RANGE, outOfRange.code());
        assertEquals(GatewayErrorCode.STALE_VERSION, stale.code());
    }
}
