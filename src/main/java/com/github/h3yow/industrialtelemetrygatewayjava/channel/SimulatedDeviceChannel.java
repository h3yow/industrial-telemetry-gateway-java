package com.github.h3yow.industrialtelemetrygatewayjava.channel;

import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 进程内模拟设备通道：用内存数组替代真实 PLC。
 *
 * <p>行为特性：
 * <ul>
 *   <li>地址越界抛 {@link ChannelException.InvalidAddress}</li>
 *   <li>写入值超出值域抛 {@link ChannelException.ValueOutOfRange}</li>
 *   <li>通道关闭后任何读写抛 {@link ChannelException.Closed}</li>
 *   <li>支持故障注入（如下一次读写抛出超时），用于测试异常归一化</li>
 *   <li>支持模拟 IO 延迟，便于构造采样与写入并发的场景</li>
 * </ul>
 */
@Component
public class SimulatedDeviceChannel implements DeviceChannel {

    public static final int DEFAULT_ADDRESS_COUNT = 64;
    public static final double MIN_VALUE = -100_000d;
    public static final double MAX_VALUE = 100_000d;

    private final AtomicLongArray registers;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Queue<ChannelException> injectedFailures = new ConcurrentLinkedQueue<>();

    /** 模拟单次 IO 延迟（毫秒），测试可调整 */
    private volatile long ioLatencyMillis = 1;

    public SimulatedDeviceChannel() {
        this(DEFAULT_ADDRESS_COUNT);
    }

    public SimulatedDeviceChannel(int addressCount) {
        this.registers = new AtomicLongArray(addressCount);
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public double read(int address) throws ChannelException {
        simulateIo();
        checkOpen();
        checkAddress(address);
        throwInjected();
        return Double.longBitsToDouble(registers.get(address));
    }

    @Override
    public void write(int address, double value) throws ChannelException {
        simulateIo();
        checkOpen();
        checkAddress(address);
        checkRange(value);
        throwInjected();
        registers.set(address, Double.doubleToRawLongBits(value));
    }

    @Override
    public int addressCount() {
        return registers.length();
    }

    @Override
    public void close() {
        open.set(false);
    }

    /** 模拟设备恢复连接 */
    public void reopen() {
        open.set(true);
    }

    /** 故障注入：下一次读写操作将抛出该异常（仅消费一次） */
    public void failNextWith(ChannelException failure) {
        injectedFailures.add(failure);
    }

    public void setIoLatencyMillis(long millis) {
        this.ioLatencyMillis = millis;
    }

    private void simulateIo() throws ChannelException.Timeout {
        long latency = ioLatencyMillis;
        if (latency <= 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException.Timeout("interrupted during simulated io");
        }
    }

    private void checkOpen() throws ChannelException.Closed {
        if (!open.get()) {
            throw new ChannelException.Closed("channel is closed");
        }
    }

    private void checkAddress(int address) throws ChannelException.InvalidAddress {
        if (address < 0 || address >= registers.length()) {
            throw new ChannelException.InvalidAddress(
                    "address " + address + " out of range [0," + registers.length() + ")");
        }
    }

    private void checkRange(double value) throws ChannelException.ValueOutOfRange {
        if (Double.isNaN(value) || value < MIN_VALUE || value > MAX_VALUE) {
            throw new ChannelException.ValueOutOfRange(
                    "value " + value + " out of range [" + MIN_VALUE + "," + MAX_VALUE + "]");
        }
    }

    private void throwInjected() throws ChannelException {
        ChannelException failure = injectedFailures.poll();
        if (failure != null) {
            throw failure;
        }
    }
}
