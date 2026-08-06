package org.gms.domain.script;

/**
 * DomainScriptMarker 模块标记类（M0 占位）。
 *
 * <p>GraalVM 脚本引擎（宿主对象契约 cm/qm/em/rm/im 接口化）
 *
 * <p>存在意义：让架构测试（ArchUnit）能扫描到本模块的类，从而固化"模块边界 + 依赖方向"
 * 规则。M1 起该模块的实际类替换此占位。
 */
public final class DomainScriptMarker {

    private DomainScriptMarker() {
    }
}
