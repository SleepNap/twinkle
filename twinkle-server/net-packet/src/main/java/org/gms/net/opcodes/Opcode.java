package org.gms.net.opcodes;

/**
 * v83 协议操作码统一接口（架构红线 1：字节级兼容）。
 *
 * <p>收发两端 opcode 均为 short（小端序写入包首）。实现枚举提供数值与名称，
 * 供协议层、HandlerRegistry、调试工具统一使用。
 */
public interface Opcode {

    /**
     * 操作码数值（0~65535，写入包时取低 16 位小端）。
     */
    int getValue();

    /**
     * 操作码名称（枚举名，调试 / 日志用）。
     */
    String getName();
}
