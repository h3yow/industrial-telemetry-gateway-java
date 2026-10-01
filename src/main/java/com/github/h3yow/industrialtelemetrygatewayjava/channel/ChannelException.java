package com.github.h3yow.industrialtelemetrygatewayjava.channel;

/**
 * 底层通道异常基类。模拟通道抛出的所有异常都继承自它，
 * 上层服务负责归一化，不允许把该异常原样透出。
 */
public class ChannelException extends Exception {

    public ChannelException(String message) {
        super(message);
    }

    /** 读写超时 */
    public static class Timeout extends ChannelException {
        public Timeout(String message) {
            super(message);
        }
    }

    /** 非法地址 */
    public static class InvalidAddress extends ChannelException {
        public InvalidAddress(String message) {
            super(message);
        }
    }

    /** 值越界 */
    public static class ValueOutOfRange extends ChannelException {
        public ValueOutOfRange(String message) {
            super(message);
        }
    }

    /** 通道已关闭 */
    public static class Closed extends ChannelException {
        public Closed(String message) {
            super(message);
        }
    }
}
