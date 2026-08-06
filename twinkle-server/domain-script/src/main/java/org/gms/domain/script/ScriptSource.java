package org.gms.domain.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 单条脚本条目（路径 + 源码 + 最后修改时间）。
 *
 * <p>脚本目录里一个 {@code .js} 文件对应一个 {@code ScriptSource}；加载时记录 mtime，
 * 用于 L2 热重载判定（mtime 变化即视为已修改）。
 */
public final class ScriptSource {

    private final Path file;
    private final String content;
    private final long lastModified;

    public ScriptSource(Path file, String content, long lastModified) {
        this.file = Objects.requireNonNull(file, "file");
        this.content = Objects.requireNonNull(content, "content");
        this.lastModified = lastModified;
    }

    /** 从文件读取（一次性快照：内容 + mtime）。 */
    public static ScriptSource read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        return new ScriptSource(file, content, mtime);
    }

    public Path file() {
        return file;
    }

    public String content() {
        return content;
    }

    public long lastModified() {
        return lastModified;
    }
}
