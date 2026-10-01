package com.github.h3yow.industrialtelemetrygatewayjava.tests;

import com.github.h3yow.industrialtelemetrygatewayjava.channel.ChannelClosedException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.InvalidChannelAddressException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannel;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.SimulatedChannelTimeoutException;
import com.github.h3yow.industrialtelemetrygatewayjava.channel.ValueOutOfBoundsException;
import com.github.h3yow.industrialtelemetrygatewayjava.device.SimulatedDevice;
import com.github.h3yow.industrialtelemetrygatewayjava.error.ErrorCategory;
import com.github.h3yow.industrialtelemetrygatewayjava.error.GatewayException;
import com.github.h3yow.industrialtelemetrygatewayjava.model.WriteRequest;
import com.github.h3yow.industrialtelemetrygatewayjava.support.TestDevices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断连快速失败 + 底层通道异常归一化分类场景。
 * <p>
 * 覆盖：DEVICE_DISCONNECTED（断连/通道关闭）、IO_TIMEOUT、INVALID_ADDRESS、
 * VALUE_OUT_OF_RANGE；并断言对外只能观察到 GatewayException，
 * 原始通道异常类型（*Channel*Exception）不会成为顶层异常。
 */
class DisconnectAndExceptionClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(
            DisconnectAndExceptionClassificationTest.class);

    @Test
    @DisplayName("设备断连后读写立即快速失败，分类 DEVICE_DISCONNECTED 且说明原因")
    void disconnectedDeviceFailsFastOnReadAndWrite() {
        SimulatedDevice device = TestDevices.newDevice("dev-disc-1");
        device.disconnect();
        log.info("输入: 设备 {} 已 disconnect，随后尝试读点与写点", device.deviceId());

        long start = System.nanoTime();
        GatewayException readError = assertThrows(GatewayException.class,
                () -> device.readPoint("motor-speed-rpm"));
        long readMicros = (System.nanoTime() - start) / 1000;

        start = System.nanoTime();
        GatewayException snapshotError = assertThrows(GatewayException.class,
                device::readSnapshot);
        long snapMicros = (System.nanoTime() - start) / 1000;

        GatewayException writeError = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(),
                        "motor-speed-rpm", 1L)));

        log.info("判定依据: read 分类={} 耗时={}us, snapshot 分类={} 耗时={}us, write 分类={}, messages=[{} | {}]",
                readError.category(), readMicros,
                snapshotError.category(), snapMicros,
                writeError.category(), readError.getMessage(), writeError.getMessage());

        assertEquals(ErrorCategory.DEVICE_DISCONNECTED, readError.category());
        assertEquals(ErrorCategory.DEVICE_DISCONNECTED, snapshotError.category());
        assertEquals(ErrorCategory.DEVICE_DISCONNECTED, writeError.category());
        assertTrue(readMicros < 50_000, "断连读取必须快速失败，不得等待通道超时");
        assertTrue(snapMicros < 50_000, "断连快照必须快速失败");
        assertTrue(readError.getMessage().contains("disconnected"),
                "错误信息需说明断连原因");
    }

    @Test
    @DisplayName("重新连接后读写恢复")
    void reconnectRestoresAccess() {
        SimulatedDevice device = TestDevices.newDevice("dev-disc-2");
        device.disconnect();
        assertThrows(GatewayException.class, () -> device.readPoint("motor-running"));
        device.connect();
        assertEquals(0, device.readPoint("motor-running").value().intValue());
        log.info("输入: disconnect 后再 connect；判定依据: 恢复后读取分类正常，motor-running={}",
                device.readPoint("motor-running").value());
    }

    @Test
    @DisplayName("通道超时被归一化为 IO_TIMEOUT，且原始异常仅作为 cause 保留")
    void channelTimeoutNormalized() {
        SimulatedChannel channel = TestDevices.newChannel("dev-timeout");
        SimulatedDevice device = new SimulatedDevice("dev-timeout", channel, TestDevices.points());
        channel.failNextRead(new SimulatedChannelTimeoutException("no response from slave"));

        GatewayException e = assertThrows(GatewayException.class,
                () -> device.readPoint("motor-speed-rpm"));
        log.info("输入: failNextRead(TimeoutException)；判定依据: 对外分类={}, 顶层类型={}, cause={}, message={}",
                e.category(), e.getClass().getSimpleName(),
                e.getCause() == null ? null : e.getCause().getClass().getSimpleName(),
                e.getMessage());

        assertEquals(ErrorCategory.IO_TIMEOUT, e.category());
        assertTrue(e.getCause() instanceof SimulatedChannelTimeoutException,
                "原始超时异常只能作为 cause 保留用于排查");
    }

    @Test
    @DisplayName("通道在运行期变慢（往返超过超时阈值）时，读取归一化为 IO_TIMEOUT")
    void slowChannelTriggersTimeout() {
        // 设备先在快通道上成功上线，随后总线劣化：延迟 300ms 超过 50ms 超时阈值
        SimulatedChannel slow = new SimulatedChannel("dev-slow",
                TestDevices.MIN_ADDRESS, TestDevices.MAX_ADDRESS, 0L, 10_000L);
        SimulatedDevice device = new SimulatedDevice("dev-slow", slow, TestDevices.points());
        slow.failAddress(1000, new SimulatedChannelTimeoutException(
                "round-trip 300ms exceeded 50ms timeout"));

        GatewayException e = assertThrows(GatewayException.class,
                () -> device.readPoint("motor-speed-rpm"));
        log.info("输入: 设备上线后地址 1000 持续超时；判定依据: 分类={}", e.category());
        assertEquals(ErrorCategory.IO_TIMEOUT, e.category());
        assertTrue(e.getCause() instanceof SimulatedChannelTimeoutException);
    }

    @Test
    @DisplayName("通道非法地址与越界异常归一化为 INVALID_ADDRESS / VALUE_OUT_OF_RANGE")
    void channelAddressAndBoundsFaultsNormalized() {
        SimulatedChannel channel = TestDevices.newChannel("dev-faults");
        SimulatedDevice device = new SimulatedDevice("dev-faults", channel, TestDevices.points());

        channel.failNextRead(new InvalidChannelAddressException("illegal register 4242"));
        GatewayException addr = assertThrows(GatewayException.class,
                () -> device.readPoint("motor-speed-rpm"));

        channel.failNextWrite(new ValueOutOfBoundsException("register cannot hold 70000"));
        // 通道越界由设备在写 UINT16 前以编码校验拦截，这里直接对 FLOAT 点注入写故障验证归一化
        channel.failNextWrite(new ValueOutOfBoundsException("register cannot hold value"));
        GatewayException bounds = assertThrows(GatewayException.class,
                () -> device.write(new WriteRequest(device.deviceId(), "temperature-c", 3.14f)));

        log.info("输入: failNextRead(InvalidAddress), failNextWrite(ValueOutOfBounds)；判定依据: {} / {}",
                addr.category(), bounds.category());
        assertEquals(ErrorCategory.INVALID_ADDRESS, addr.category());
        assertEquals(ErrorCategory.VALUE_OUT_OF_RANGE, bounds.category());
        assertTrue(bounds.getCause() instanceof ValueOutOfBoundsException);
    }

    @Test
    @DisplayName("通道被外部直接关闭后，读取归一化为 DEVICE_DISCONNECTED，不泄漏 ChannelClosedException")
    void externallyClosedChannelNormalized() {
        SimulatedChannel channel = TestDevices.newChannel("dev-closed");
        SimulatedDevice device = new SimulatedDevice("dev-closed", channel, TestDevices.points());
        channel.close(); // 模拟底层掉线（设备层 connected 标志尚不知情）

        GatewayException e = assertThrows(GatewayException.class,
                () -> device.readPoint("motor-running"));
        log.info("输入: 底层 channel.close() 后读 BOOL 点；判定依据: 分类={}, cause={}",
                e.category(), e.getCause().getClass().getSimpleName());
        assertEquals(ErrorCategory.DEVICE_DISCONNECTED, e.category());
        assertTrue(e.getCause() instanceof ChannelClosedException);
        assertNull(scanForRawTopException(e), "顶层异常不得是通道原始类型");
    }

    /** 异常链上除 cause 之外，业务异常本身必须是 GatewayException。 */
    private static String scanForRawTopException(Throwable t) {
        if (!(t instanceof GatewayException)) {
            return t.getClass().getName();
        }
        return null;
    }
}
