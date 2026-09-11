package org.gms.module;
import java.nio.charset.StandardCharsets;

import org.gms.i18n.I18n;

import java.io.IOException;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.GenericArrayType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** 对稳定契约及其暴露的宿主类型计算字节摘要，拒绝用新接口编译的包加载到旧宿主。 */
public final class ContractFingerprint {
    private ContractFingerprint() { }

    public static String calculate(List<Class<?>> roots) throws IOException {
        var pending = new ArrayDeque<Class<?>>(roots);
        Set<Class<?>> seen = new HashSet<>();
        var bytes = new TreeMap<String, byte[]>();
        while (!pending.isEmpty()) {
            Class<?> type = pending.remove();
            if (type.isArray()) { pending.add(type.componentType()); continue; }
            if (!type.getName().startsWith("org.gms.") || !seen.add(type)) continue;
            String path = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream(path)) {
                if (input == null) throw new IOException(I18n.message("error.module.bytecode_missing", type.getName()));
                bytes.put(type.getName(), input.readAllBytes());
            }
            if (type.getSuperclass() != null) pending.add(type.getSuperclass());
            pending.addAll(List.of(type.getInterfaces()));
            pending.addAll(List.of(type.getDeclaredClasses()));
            for (var method : type.getDeclaredMethods()) {
                add(method.getGenericReturnType(), pending);
                for (Type parameter : method.getGenericParameterTypes()) add(parameter, pending);
                pending.addAll(List.of(method.getExceptionTypes()));
            }
            for (var field : type.getDeclaredFields()) add(field.getGenericType(), pending);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            bytes.forEach((name, value) -> { digest.update(name.getBytes(StandardCharsets.UTF_8)); digest.update(value); });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void add(Type type, ArrayDeque<Class<?>> pending) {
        if (type instanceof Class<?> cls) pending.add(cls);
        else if (type instanceof ParameterizedType generic) {
            add(generic.getRawType(), pending);
            for (Type argument : generic.getActualTypeArguments()) add(argument, pending);
        } else if (type instanceof GenericArrayType array) add(array.getGenericComponentType(), pending);
    }
}
