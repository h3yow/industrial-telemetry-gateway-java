package com.github.h3yow.industrialtelemetrygatewayjava.channel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟现场总线通道（进程内替代真实 PLC/Modbus）。
 * <p>
 * 职责边界：只做寄存器/线圈的原始读写、地址范围校验、延迟模拟、
 * 关闭态校验与故障注入；不理解点位、类型编码与版本。
 * 所有错误以 {@link SimulatedChannelException} 子类抛出，由设备层归一化。
 * <p>
 * 保持寄存器与线圈使用同一线性地址空间但不重叠（由设备层分配）：
 * 线圈占用低位地址，保持寄存器占用其后的地址。
 */
public class SimulatedChannel {

    private final String deviceId;
    private final int minAddress;
    private final int maxAddress;
    private final long latencyMillis;
    private final long timeoutMillis;

    private final int[] registers;
    private final boolean[] coils;
    private volatile boolean open;

    /** 下一次读/写抛出的一次性故障（优先级最高）。 */
    private final AtomicReference<SimulatedChannelException> nextReadFault = new AtomicReference<>();
    private final AtomicReference<SimulatedChannelException> nextWriteFault = new AtomicReference<>();
    /** 指定地址上的持续性故障。 */
    private final Map<Integer, SimulatedChannelException> addressFaults = new HashMap<>();

    public SimulatedChannel(String deviceId, int minAddress, int maxAddress,
                            long latencyMillis, long timeoutMillis) {
        this.deviceId = deviceId;
        this.minAddress = minAddress;
        this.maxAddress = maxAddress;
        this.latencyMillis = latencyMillis;
        this.timeoutMillis = timeoutMillis;
        int span = maxAddress - minAddress + 1;
        this.registers = new int[span];
        this.coils = new boolean[span];
        this.open = false;
    }

    /** 打开通道。 */
    public synchronized void open() {
        this.open = true;
    }

    /** 关闭通道；关闭后任何读写抛 {@link ChannelClosedException}。 */
    public synchronized void close() {
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** 令下一次读（任意地址）抛出指定异常，用于故障注入测试。 */
    public void failNextRead(SimulatedChannelException exception) {
        nextReadFault.set(exception);
    }

    /** 令下一次写（任意地址）抛出指定异常。 */
    public void failNextWrite(SimulatedChannelException exception) {
        nextWriteFault.set(exception);
    }

    /** 令指定地址的后续读写持续抛出指定异常。 */
    public void failAddress(int address, SimulatedChannelException exception) {
        synchronized (addressFaults) {
            addressFaults.put(address, exception);
        }
    }

    /** 清除全部注入故障。 */
    public void clearFaults() {
        nextReadFault.set(null);
        nextWriteFault.set(null);
        synchronized (addressFaults) {
            addressFaults.clear();
        }
    }

    /** 读连续保持寄存器（原始 16 位无符号值）。 */
    public int[] readRegisters(int startAddress, int count) {
        SimulatedChannelException oneShot = nextReadFault.getAndSet(null);
        if (oneShot != null) {
            throw oneShot;
        }
        ensureOpen();
        checkRange(startAddress, count);
        SimulatedChannelException persistent = persistentFault(startAddress);
        if (persistent != null) {
            throw persistent;
        }
        simulateLatency();
        synchronized (this) {
            int offset = startAddress - minAddress;
            int[] copy = new int[count];
            System.arraycopy(registers, offset, copy, 0, count);
            return copy;
        }
    }

    /** 写连续保持寄存器。 */
    public void writeRegisters(int startAddress, int[] values) {
        SimulatedChannelException oneShot = nextWriteFault.getAndSet(null);
        if (oneShot != null) {
            throw oneShot;
        }
        ensureOpen();
        checkRange(startAddress, values.length);
        SimulatedChannelException persistent = persistentFault(startAddress);
        if (persistent != null) {
            throw persistent;
        }
        simulateLatency();
        synchronized (this) {
            int offset = startAddress - minAddress;
            System.arraycopy(values, 0, registers, offset, values.length);
        }
    }

    /** 读线圈。 */
    public boolean readCoil(int address) {
        SimulatedChannelException oneShot = nextReadFault.getAndSet(null);
        if (oneShot != null) {
            throw oneShot;
        }
        ensureOpen();
        checkRange(address, 1);
        SimulatedChannelException persistent = persistentFault(address);
        if (persistent != null) {
            throw persistent;
        }
        simulateLatency();
        synchronized (this) {
            return coils[address - minAddress];
        }
    }

    /** 写线圈。 */
    public void writeCoil(int address, boolean value) {
        SimulatedChannelException oneShot = nextWriteFault.getAndSet(null);
        if (oneShot != null) {
            throw oneShot;
        }
        ensureOpen();
        checkRange(address, 1);
        SimulatedChannelException persistent = persistentFault(address);
        if (persistent != null) {
            throw persistent;
        }
        simulateLatency();
        synchronized (this) {
            coils[address - minAddress] = value;
        }
    }

    private void ensureOpen() {
        if (!open) {
            throw new ChannelClosedException("channel of device " + deviceId + " is closed");
        }
    }

    private void checkRange(int startAddress, int count) {
        if (startAddress < minAddress || count <= 0
                || (long) startAddress + count - 1 > maxAddress) {
            throw new InvalidChannelAddressException(
                    "address out of range: start=" + startAddress + ", count=" + count
                            + ", range=[" + minAddress + "," + maxAddress + "] on " + deviceId);
        }
    }

    private SimulatedChannelException persistentFault(int address) {
        synchronized (addressFaults) {
            return addressFaults.get(address);
        }
    }

    /**
     * 模拟总线往返延迟；延迟超过超时阈值即视为无响应。
     * 放在通道内同步段之外，不影响寄存器本身的内存一致性。
     */
    private void simulateLatency() {
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatedChannelException("interrupted while waiting on channel " + deviceId, e);
        }
        if (latencyMillis > timeoutMillis) {
            throw new SimulatedChannelTimeoutException(
                    "io timeout after " + latencyMillis + "ms (threshold=" + timeoutMillis + "ms) on "
                            + deviceId);
        }
    }
}
