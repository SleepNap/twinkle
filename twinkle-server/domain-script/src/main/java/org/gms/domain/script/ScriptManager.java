package org.gms.domain.script;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.script.host.Cm;
import org.gms.domain.script.host.Em;
import org.gms.domain.script.host.Im;
import org.gms.domain.script.host.Qm;
import org.gms.domain.script.host.Rm;
import org.gms.i18n.I18n;
import org.graalvm.polyglot.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 脚本管理器（架构 M2-4 脚本引擎：统一 reload 入口 + 宿主契约注入 + L2 热重载）。
 *
 * <p><b>L2 热重载</b>（架构 5.3 / M2-4 第 4 项）：
 * <ul>
 *   <li>{@link #reload()} 触发 {@link ScriptRepository#reload()} 重新扫描文件（基于 mtime 判定变化）。</li>
 *   <li>已读入的 {@link ScriptEngine} context 不持有脚本源码的全局引用——每次执行按 key 重新
 *       从当前快照取最新内容，因此 reload 后新调用自动使用新代码。</li>
 *   <li><b>不阻塞进行中的脚本</b>：当前 snapshot 是不可变 Map；正在跑的 eval 持有的是 eval 调用
 *       那一刻传入的字符串，不受 reload 影响。</li>
 * </ul>
 *
 * <p><b>宿主对象契约</b>：5 个接口（cm/qm/em/rm/im，架构 M0 第 9 项）。本管理器用绑定注入脚本。
 */
@Log4j2
public final class ScriptManager {


    private final ScriptEngine engine;
    private final ScriptRepository repository;

    public ScriptManager(ScriptEngine engine, ScriptRepository repository) {
        this.engine = Objects.requireNonNull(engine);
        this.repository = Objects.requireNonNull(repository);
    }

    /**
     * 执行指定 key 的脚本（如 {@code "nps/100"} = {@code scripts/nps/100.js}）。
     *
     * @param key      相对路径无扩展名
     * @param bindings 宿主对象（cm/qm/em/rm/im 等），键名即 JS 全局变量名
     * @return 脚本最后表达式值；key 不存在返回 empty
     */
    public Optional<Value> run(String key, Map<String, Object> bindings) {
        ScriptSource src = repository.loadAll().get(key);
        if (src == null) {
            log.warn(I18n.message("log.script.not_found"), key);
            return Optional.empty();
        }
        // 注入 cm/qm/em/rm/im 等宿主契约 + 用户传入的额外绑定
        Map<String, Object> all = new LinkedHashMap<>(bindings);
        putIfNotNull(all, "cm", hostOf(bindings, Cm.class));
        putIfNotNull(all, "qm", hostOf(bindings, Qm.class));
        putIfNotNull(all, "em", hostOf(bindings, Em.class));
        putIfNotNull(all, "rm", hostOf(bindings, Rm.class));
        putIfNotNull(all, "im", hostOf(bindings, Im.class));
        return Optional.of(engine.eval(src.content(), all));
    }

    private static <T> Object hostOf(Map<String, Object> bindings, Class<T> iface) {
        for (Object v : bindings.values()) {
            if (iface.isInstance(v)) {
                return v;
            }
        }
        return null;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * 打开一个对话脚本会话（M3-5 NPC/任务多轮对话）。
     *
     * <p>从当前快照取最新源码 eval（L2 热重载天然生效——新对话用新代码）；
     * 返回的 {@link ConversationScript} 持独立 Context，可多次 {@code invoke} 推进对话，
     * 结束后调用方 {@code close}。
     *
     * @param key      脚本 key（如 {@code "nps/100"} = {@code scripts/nps/100.js}）
     * @param bindings 宿主对象（cm/qm 等），键名即 JS 全局变量名
     * @return 对话会话；key 不存在/加载失败返回 null
     */
    public ConversationScript openConversation(String key, Map<String, Object> bindings) {
        ScriptSource src = repository.loadAll().get(key);
        if (src == null) {
            log.warn(I18n.message("log.script.conversation_not_found"), key);
            return null;
        }
        return ConversationScript.open(key, src.content(), bindings);
    }

    /**
     * L2 热重载入口：重新扫描脚本目录，更新内部快照。
     * 进行中的脚本执行不受影响（持有的是 eval 调用时刻的字符串）。
     */
    public int reload() {
        int changed = repository.reload();
        log.info(I18n.message("log.script.manager_reloaded"), changed);
        return changed;
    }

    /**
     * 挂载插件脚本命名空间（架构 7.1 Script 命名空间贡献点，转发 {@link ScriptRepository#mount}）。
     *
     * @param namespace 命名空间（如 {@code acme}）
     * @param sources   该命名空间下的脚本源
     */
    public void mount(String namespace, Map<String, ScriptSource> sources) {
        repository.mount(namespace, sources);
    }

    /**
     * 卸载插件脚本命名空间（插件 unload，幂等，转发 {@link ScriptRepository#unmount}）。
     */
    public void unmount(String namespace) {
        repository.unmount(namespace);
    }

    /** 脚本根目录（用于诊断/启动校验）。 */
    public java.nio.file.Path root() {
        return repository.root();
    }
}
