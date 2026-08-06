package org.gms.net.netty;

/**
 * NetNettyMarker 模块标记类（M0 占位）。
 *
 * <p>Netty IO（客户端 v83 协议 + 内部通信复用）
 *
 * <p>存在意义：让架构测试（ArchUnit）能扫描到本模块的类，从而固化"模块边界 + 依赖方向"
 * 规则。M1 起该模块的实际类替换此占位。
 */
public final class NetNettyMarker {

    private NetNettyMarker() {
    }
}
