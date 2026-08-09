package org.gms.wz;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.mob.MobData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 怪物数据加载器（架构 6.4：`twinkle.wz.path` 直接指定 WZ 目录，单份数据）。
 *
 * <p>遍历 {@code Mob.wz} 下所有 {@code *.img.xml}（一个文件一个怪物），
 * 填 {@link MobData} 的 info 字段（v83 字段名：maxHP/PADamage/PDDamage 等）。
 */
@Log4j2
public final class MobLoader {


    private final Path wzRoot;

    public MobLoader(Path wzRoot) {
        this.wzRoot = Objects.requireNonNull(wzRoot, "wzRoot");
    }

    /** 解析 Mob.wz 全部怪物（id → MobData）。 */
    public Map<Integer, MobData> loadAll() {
        Path mobWz = wzRoot.resolve("Mob.wz");
        Map<Integer, MobData> mobs = new HashMap<>();
        try (var stream = Files.walk(mobWz)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".img.xml"))
                    .forEach(p -> {
                        MobData data = parseMob(p);
                        mobs.put(data.getMobId(), data);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("遍历 Mob.wz 失败: " + mobWz, e);
        }
        log.info("Mob.wz 解析完成：{} 个怪物（根={}）", mobs.size(), wzRoot);
        return mobs;
    }

    private MobData parseMob(Path file) {
        WzNode root = WzXmlParser.parse(file);
        // 文件名即 mobId（如 0100100.img.xml）；root.name() = "0100100.img"
        String mobId = root.name().replace(".img", "");
        MobData data = new MobData(Integer.parseInt(mobId));
        root.child("info").ifPresent(info -> {
            info.getInt("level").ifPresent(data::setLevel);
            info.getInt("maxHP").ifPresent(data::setMaxHp);
            info.getInt("maxMP").ifPresent(data::setMaxMp);
            info.getInt("exp").ifPresent(data::setExp);
            info.getInt("PADamage").ifPresent(data::setPad);
            info.getInt("PDDamage").ifPresent(data::setPdd);
            info.getInt("MADamage").ifPresent(data::setMad);
            info.getInt("MDDamage").ifPresent(data::setMdd);
            info.getInt("acc").ifPresent(data::setAcc);
            info.getInt("eva").ifPresent(data::setEva);
            info.getInt("speed").ifPresent(data::setSpeed);
            info.getInt("pushed").ifPresent(data::setPushed);
            info.getInt("boss").ifPresent(b -> data.setBoss(b == 1));
            info.getInt("undead").ifPresent(u -> data.setUndead(u == 1));
            info.getInt("bodyAttack").ifPresent(b -> data.setBodyAttack(b == 1));
        });
        return data;
    }
}
