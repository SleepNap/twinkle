package org.gms.domain.script;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.Map;

/**
 * 对话脚本会话（M3-5：NPC/任务多轮对话的脚本执行单元）。
 *
 * <p>与 {@link ScriptEngine}（单共享 Context、每次整文件 eval）不同：本类为<b>一次对话</b>
 * 持有一个独立 GraalVM Context，脚本文件只 eval 一次（加载函数定义），后续每轮经
 * {@link #invoke} 调用函数——脚本全局 {@code var} 变量（如经典 status、nextlevel 的
 * g_Select）跨多轮保持。对话结束/断链 {@link #close} 释放 Context。
 *
 * <p>热重载语义：新对话从 {@link ScriptRepository} 最新快照取源码（天然 L2 生效），
 * 进行中的会话不受 reload 影响（持有的是打开时刻的字符串）。
 *
 * <p>引擎预算：对话级 Context 较 ScriptEngine 单例更重（每次新建），但 2C2G 下对话
 * 并发低（每玩家同时至多一个），可接受；后续可引入 Context 池优化。
 */
@Log4j2
public final class ConversationScript implements AutoCloseable {



    private final Context context;

    private ConversationScript() {
        this.context = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * 打开一个对话脚本：从最新快照取源码 eval 一次（注入宿主），随后可 invoke 函数。
     *
     * @param key      脚本 key（如 {@code "nps/100"})
     * @param source   脚本源码（由调用方从最新快照取，保证 L2 热重载）
     * @param bindings 宿主对象（cm/qm 等），键名即 JS 全局变量
     * @return 对话会话；源码缺失返回 empty
     */
    public static ConversationScript open(String key, String source, Map<String, Object> bindings) {
        if (source == null) {
            return null;
        }
        ConversationScript session = new ConversationScript();
        try {
            bindings.forEach(session.context.getBindings("js")::putMember);
            session.context.eval(Source.create("js", source));
            return session;
        } catch (RuntimeException e) {
            log.error(I18n.message("log.script.conversation_load_failed"), key, e);
            session.close();
            return null;
        }
    }

    /**
     * 调用脚本函数（多轮对话恢复）。
     *
     * @param fn   函数名（如 "start"、"action"、"levelXxx"）
     * @param args 参数（v83 脚本约定：mode/type/selection）
     */
    public Value invoke(String fn, Object... args) {
        Value member = context.getBindings("js").getMember(fn);
        if (member == null || !member.canExecute()) {
            return null;
        }
        return member.execute(args);
    }

    /** 是否存在某函数（nextlevel 派发前探测）。 */
    public boolean hasFunction(String fn) {
        Value member = context.getBindings("js").getMember(fn);
        return member != null && member.canExecute();
    }

    @Override
    public void close() {
        try {
            context.close();
        } catch (Exception e) {
            log.warn(I18n.message("log.script.conversation_context_close_failed"), e);
        }
    }
}
