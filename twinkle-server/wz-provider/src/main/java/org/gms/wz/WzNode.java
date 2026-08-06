package org.gms.wz;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * WZ 解包 XML 的通用数据树节点（imgdir = 容器，int/string/float/vector = 叶子）。
 *
 * <p>只做数据持有与按名访问，不持有业务语义。解析由 {@link WzXmlParser} 负责，
 * 填充（MapleMap 等）由各 Loader 负责。WzNode 可变（解析期构建），构建后只读访问。
 */
public final class WzNode {

    private final String name;
    private final Map<String, WzNode> children = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();

    public WzNode(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void putChild(WzNode child) {
        children.put(child.name(), child);
    }

    public Optional<WzNode> child(String childName) {
        return Optional.ofNullable(children.get(childName));
    }

    /** 全部子节点（不可变视图）。 */
    public Map<String, WzNode> children() {
        return Map.copyOf(children);
    }

    /** 全部叶子值（不可变视图，String → 原始文本）。 */
    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    public void putValue(String key, String value) {
        values.put(key, value);
    }

    public Optional<String> getString(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public OptionalInt getInt(String key) {
        String v = values.get(key);
        if (v == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public OptionalDouble getDouble(String key) {
        String v = values.get(key);
        if (v == null) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
