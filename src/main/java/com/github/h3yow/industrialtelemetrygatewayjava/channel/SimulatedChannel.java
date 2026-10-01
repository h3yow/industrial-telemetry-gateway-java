package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/**
 * 模拟现场总线通道（进程内替代真实 PLC/Modbus）。
 * <p>
 * 职责边界：只做寄存器/线圈的原始读写、地址范围校验、延迟模拟、
 * 关闭态校验与故障注入；不理解点位、类型编码与版本。
 * 所有错误以 {@link SimulatedChannelException} 子类抛出，由设备层归一化。
 */
public class SimulatedChannel {

    public SimulatedChannel(String deviceId, int minAddress, int maxAddress, long latencyMillis) {
        // 骨架占位
    }

    /** 打开通道。 */
    public void open() {
        // 骨架占位
    }

    /** 关闭通道；关闭后任何读写抛 {@link ChannelClosedException}。 */
    public void close() {
        // 骨架占位
    }

    public boolean isOpen() {
        return false;
    }

    /** 读连续保持寄存器（原始 16 位值）。 */
    public int[] readRegisters(int startAddress, int count) {
        return new int[0];
    }

    /** 写连续保持寄存器。 */
    public void writeRegisters(int startAddress, int[] values) {
        // 骨架占位
    }

    /** 读线圈。 */
    public boolean readCoil(int address) {
        return false;
    }

    /** 写线圈。 */
    public void writeCoil(int address, boolean value) {
        // 骨架占位
    }

    /** 令下一次读（任意地址）抛出指定异常，用于故障注入测试。 */
    public void failNextRead(SimulatedChannelException exception) {
        // 骨架占位
    }

    /** 令下一次写（任意地址）抛出指定异常。 */
    public void failNextWrite(SimulatedChannelException exception) {
        // 骨架占位
    }

    /** 令指定地址的后续读写持续抛出指定异常。 */
    public void failAddress(int address, SimulatedChannelException exception) {
        // 骨架占位
    }

    /** 清除全部注入故障。 */
    public void clearFaults() {
        // 骨架占位
    }
}
