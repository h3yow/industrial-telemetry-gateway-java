package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.ChannelException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedDeviceChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.core.PointStore;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayErrorCode;
import com.github.h3yow.industrialtelemetrygatewayjava.service.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.service.TelemetryGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底层通道异常归一化与断连快速失败：
 * 超时、非法地址、值越界、通道关闭必须映射为可区分的错误码，
 * 且底层 ChannelException 不得原样透出；设备断连时读取要尽快失败。
 */
class DisconnectAndErrorClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(DisconnectAndErrorClassificationTest.class);
    private static final int ADDRESS = 5;

    private SimulatedDeviceChannel channel;
    private TelemetryGatewayService service;

    @BeforeEach
    void setUp() {
        channel = new SimulatedDeviceChannel();
        channel.setIoLatencyMillis(0);
        service = new TelemetryGatewayService(channel, new PointStore());
        service.acquireOnce();
    }

    @Test
    void timeoutIsNormalizedToChannelTimeout() {
        channel.failNextWith(new ChannelException.Timeout("simulated read timeout"));
        log.info("输入: 注入一次底层超时异常后写入 address={}", ADDRESS);

        GatewayException e = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 1.0, service.readPoint(ADDRESS).version()));
        log.info("判定依据: 错误码={}(应=CHANNEL_TIMEOUT), 异常类型={}(不应暴露ChannelException), message={}",
                e.code(), e.getClass().getSimpleName(), e.getMessage());

        assertEquals(GatewayErrorCode.CHANNEL_TIMEOUT, e.code());
        assertEquals(GatewayException.class, e.getClass(), "对外只能是统一的 GatewayException");
        assertFalse(e.getMessage().contains("simulated"), "对外消息不应照搬底层异常原文");
    }

    @Test
    void invalidAddressAndOutOfRangeAreNormalizedSeparately() {
        GatewayException invalid = assertThrows(GatewayException.class,
                () -> service.writePoint(-1, 1.0, 0));
        GatewayException outOfRange = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, SimulatedDeviceChannel.MAX_VALUE + 1, 0));
        log.info("判定依据: 非法地址→{}(应=INVALID_ADDRESS), 值越界→{}(应=VALUE_OUT_OF_RANGE)",
                invalid.code(), outOfRange.code());
        assertEquals(GatewayErrorCode.INVALID_ADDRESS, invalid.code());
        assertEquals(GatewayErrorCode.VALUE_OUT_OF_RANGE, outOfRange.code());
    }

    @Test
    void closedChannelFailsFastOnReadAndWrite() {
        channel.close();
        log.info("输入: 通道已关闭, 分别执行读、写、批量读");

        long start = System.nanoTime();
        GatewayException readError = assertThrows(GatewayException.class, () -> service.readPoint(ADDRESS));
        GatewayException writeError = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 1.0, 0));
        GatewayException batchError = assertThrows(GatewayException.class,
                () -> service.readBatch(Set.of(ADDRESS)));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        log.info("判定依据: 读→{}, 写→{}, 批量读→{}(均应=DEVICE_UNAVAILABLE), 三次调用总耗时={}ms(应快速返回)",
                readError.code(), writeError.code(), batchError.code(), elapsedMillis);

        assertEquals(GatewayErrorCode.DEVICE_UNAVAILABLE, readError.code());
        assertEquals(GatewayErrorCode.DEVICE_UNAVAILABLE, writeError.code());
        assertEquals(GatewayErrorCode.DEVICE_UNAVAILABLE, batchError.code());
        assertTrue(elapsedMillis < 1000, "断连时必须快速失败而不是阻塞等待");
    }

    @Test
    void acquisitionIsSkippedWhileDisconnectedAndResumesAfterReconnect() {
        long versionBefore = service.readPoint(ADDRESS).version();
        log.info("输入: 断连前版本={}, 关闭通道后连续触发两轮采集", versionBefore);

        channel.close();
        service.acquireOnce();
        service.acquireOnce();

        channel.reopen();
        long versionAfterDisconnect = service.readPoint(ADDRESS).version();
        service.acquireOnce();
        long versionAfterReconnect = service.readPoint(ADDRESS).version();

        log.info("判定依据: 断连期间版本={}(应={}不变), 重连采集后版本={}(应前进)",
                versionAfterDisconnect, versionBefore, versionAfterReconnect);
        assertEquals(versionBefore, versionAfterDisconnect, "断连期间采集必须被跳过，版本不得前进");
        assertTrue(versionAfterReconnect > versionAfterDisconnect, "重连后采集应恢复正常");
    }

    @Test
    void injectedClosedExceptionIsNormalizedToDeviceUnavailable() {
        channel.failNextWith(new ChannelException.Closed("simulated channel closed mid-operation"));
        log.info("输入: 注入一次底层'通道已关闭'异常后写入 address={}", ADDRESS);

        GatewayException e = assertThrows(GatewayException.class,
                () -> service.writePoint(ADDRESS, 1.0, service.readPoint(ADDRESS).version()));
        log.info("判定依据: 错误码={}(应=DEVICE_UNAVAILABLE)", e.code());
        assertEquals(GatewayErrorCode.DEVICE_UNAVAILABLE, e.code());
    }
}
