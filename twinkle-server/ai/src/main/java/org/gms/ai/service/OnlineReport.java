package org.gms.ai.service;

/**
 * 在线统计报表（架构 M3-2：结构化输出，LangChain4j 自动解析 POJO）。
 *
 * <p>普通 POJO（可变字段 + 无参构造）便于 LangChain4j 结构化输出反序列化；
 * 命名字段与工具返回对齐。
 */
public final class OnlineReport {

    private int onlineCount;
    private String summary;

    public OnlineReport() {
    }

    public OnlineReport(int onlineCount, String summary) {
        this.onlineCount = onlineCount;
        this.summary = summary;
    }

    public int getOnlineCount() {
        return onlineCount;
    }

    public void setOnlineCount(int onlineCount) {
        this.onlineCount = onlineCount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
