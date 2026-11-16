package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.logic.ItemSystem;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketSession;
import org.gms.persistence.entity.GuildRecordEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.GuildRecordRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.service.intercoord.IntercoordService;
import org.gms.service.intercoord.PlayerPresenceService.PlayerPresence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 公会服务（最小闭环）：创建、邀请、接受、退出、公告、成员列表、上下线通知。
 *
 * <p>本期刻意不做：多人共建的匹配确认（{@code 0x03}/{@code 0x1E}，需要 world 级 MatchChecker，
 * 没有客户端联调无法验证时序）、GP 与公会等级、徽章变更、解散以外的扩容、联盟、BBS、公会 Boss。
 * 这些在整表包里按参考实现的空值形态写出（GP=0、allianceId=0、徽章全 0），客户端可正常解析。
 *
 * <p>状态归属：公会头缓存在频道内存，成员关系以 {@code character_records.guild_id/guild_rank}
 * 为唯一权威。写库带 {@code row_version} 乐观锁，冲突时丢弃缓存重读而不是覆盖；成员在线状态
 * 用本频道会话表判定，异频道成员按大区 Presence 显示在线、但本期不向其频道广播刷新。
 *
 * <p>已知边界：{@link #persistMembership} 用整行 {@code save} 写回角色行，与频道存档并发时存在
 * 覆盖风险；后续应改为只更新 {@code guild_id}/{@code guild_rank} 两列的仓储方法。
 */
@Log4j2
public final class GuildService {
    /** 会话属性：别人发给我的入会邀请（只在内存，不落库）。 */
    public static final String INVITE_ATTR = "guildInvite";

    private static final int CREATE_COST = 1_500_000;
    private static final int MIN_LEVEL = 10;
    private static final int MAX_NAME_BYTES = 12;
    /** 公告上限按 GBK 字节算：v83 协议字符串是 GBK，100 个汉字是 200 字节。 */
    private static final int MAX_NOTICE_BYTES = 100;
    private static final int DEFAULT_CAPACITY = 10;
    private static final long INVITE_TTL_MILLIS = 60_000L;

    /** 一次入会邀请：{@code expiresAt} 之后失效，重复邀请覆盖旧记录。 */
    public record Invite(int guildId, String guildName, String senderName, long expiresAt) { }

    private enum AcceptOutcome { OK, MISSING, FULL }

    private record AcceptResult(AcceptOutcome outcome, GuildRecordEntity head) { }

    private final int worldId;
    private final GuildRecordRepository records;
    private final PlayerCharacterRepository characters;
    private final SerialTaskQueue io;
    private final PlayerSessionRegistry sessions;
    private final IntercoordService intercoord;
    private final ItemSystem itemSystem;
    /** guildId → 公会头；只在频道执行上下文中读写。 */
    private final Map<Integer, GuildRecordEntity> cache = new HashMap<>();

    public GuildService(int worldId, GuildRecordRepository records, PlayerCharacterRepository characters,
                        SerialTaskQueue io, PlayerSessionRegistry sessions, IntercoordService intercoord,
                        ItemSystem itemSystem) {
        this.worldId = worldId;
        this.records = records;
        this.characters = characters;
        this.io = io;
        this.sessions = sessions;
        this.intercoord = intercoord;
        this.itemSystem = itemSystem;
    }

    /** 登录推送公会整表；不在公会时发空包让客户端清空窗口。 */
    public void initialize(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        if (character.getGuildId() <= 0) {
            session.send(GuildPackets.showGuildInfo(null));
            return;
        }
        refresh(session, character);
    }

    /** 创建公会：单人建会，扣固定金币，创建者成为会长（职级 1）。 */
    public void create(PacketSession session, PlayerCharacter character, String name) {
        if (character.getGuildId() > 0) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_ALREADY_IN_GUILD));
            return;
        }
        if (character.getLevel() < MIN_LEVEL) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_CANNOT_CREATE));
            return;
        }
        String guildName = name == null ? "" : name.trim();
        if (!validName(guildName)) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_NAME_TAKEN));
            return;
        }
        if (character.getMeso() < CREATE_COST) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_CANNOT_CREATE));
            return;
        }
        offload(() -> {
            if (records.findByName(worldId, guildName).isPresent()) return null;
            GuildRecordEntity head = new GuildRecordEntity();
            head.setWorld(worldId);
            head.setName(guildName);
            head.setLeaderId(character.getId());
            head.setCapacity(DEFAULT_CAPACITY);
            return records.insert(head);
        }, head -> {
            if (head == null) {
                session.send(GuildPackets.genericMessage(GuildPackets.CODE_NAME_TAKEN));
                return;
            }
            int guildId = Math.toIntExact(head.getGuildRecordId());
            if (!itemSystem.changeMeso(character, -CREATE_COST)) {
                // 后台读库期间余额被别的操作花掉：撤销刚建的公会头，避免出现「有会却没付钱」。
                cache.remove(guildId);
                offload(() -> {
                    records.delete(guildId);
                    return null;
                }, ignored -> session.send(GuildPackets.genericMessage(GuildPackets.CODE_CANNOT_CREATE)));
                return;
            }
            cache.put(guildId, head);
            character.setGuildId(guildId);
            character.setGuildRank(1);
            persistMembership(character.getId(), guildId, 1);
            log.info(I18n.message("log.guild.create"), character.getId(), guildId);
            pushInfo(session, head, List.of(memberView(character, 1)));
            broadcastPresence(character, true);
        });
    }

    /** 邀请：只对同频道在线玩家生效，邀请保存在目标会话内存中，60 秒过期。 */
    public void invite(PacketSession session, PlayerCharacter character, String targetName) {
        if (character.getGuildId() <= 0) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_NOT_IN_GUILD));
            return;
        }
        if (character.getGuildRank() > 2) return;
        GuildRecordEntity head = cache.get(character.getGuildId());
        if (head == null) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_NOT_IN_GUILD));
            return;
        }
        PacketSession target = findSession(targetName);
        if (target == null) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_CHARACTER_NOT_FOUND));
            return;
        }
        PlayerCharacter targetCharacter = target.getAttr("character");
        if (targetCharacter == null) return;
        if (targetCharacter.getGuildId() > 0) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_ALREADY_IN_GUILD));
            return;
        }
        target.setAttr(INVITE_ATTR, new Invite(character.getGuildId(), head.getName(), character.getName(),
                System.currentTimeMillis() + INVITE_TTL_MILLIS));
        target.send(GuildPackets.invite(character.getGuildId(), character.getName()));
        session.send(GuildPackets.responseMessage(0, targetCharacter.getName()));
    }

    /** 接受邀请：校验邀请仍然有效、本人未在公会、公会有空位，然后写成员关系并刷新在场成员。 */
    public void accept(PacketSession session, PlayerCharacter character, int guildId, int characterId) {
        if (characterId != character.getId()) return;
        if (character.getGuildId() > 0) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_ALREADY_IN_GUILD));
            return;
        }
        Invite invite = session.getAttr(INVITE_ATTR);
        if (invite == null || invite.guildId() != guildId
                || invite.expiresAt() < System.currentTimeMillis()) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_NOT_IN_GUILD));
            return;
        }
        session.setAttr(INVITE_ATTR, null);
        offload(() -> {
            Optional<GuildRecordEntity> head = records.findById(guildId);
            if (head.isEmpty()) return new AcceptResult(AcceptOutcome.MISSING, null);
            List<PlayerCharacterRecord> members = characters.findByGuild(guildId);
            if (members.size() >= head.get().getCapacity()) {
                return new AcceptResult(AcceptOutcome.FULL, head.get());
            }
            persistMembership(character.getId(), guildId, 5);
            return new AcceptResult(AcceptOutcome.OK, head.get());
        }, result -> {
            if (result.outcome() != AcceptOutcome.OK) {
                // 分工会不存在（45）与人数已满（41），不再把两者混同一个结果码。
                int code = result.outcome() == AcceptOutcome.MISSING
                        ? GuildPackets.CODE_NOT_IN_GUILD : GuildPackets.CODE_CAPACITY_FULL;
                session.send(GuildPackets.genericMessage(code));
                return;
            }
            GuildRecordEntity head = result.head();
            cache.put(guildId, head);
            character.setGuildId(guildId);
            character.setGuildRank(5);
            log.info(I18n.message("log.guild.join"), character.getId(), guildId);
            broadcastPresence(character, true);
            refreshAllMembers(guildId, head);
        });
    }

    /** 退出：会长退出即解散公会，普通成员只清自己的成员关系。 */
    public void leave(PacketSession session, PlayerCharacter character, int characterId, String name) {
        if (characterId != character.getId()) return;
        if (name == null || !name.equals(character.getName())) return;
        int guildId = character.getGuildId();
        if (guildId <= 0) return;
        boolean leader = character.getGuildRank() == 1;
        offload(() -> {
            Optional<GuildRecordEntity> head = records.findById(guildId);
            if (leader) {
                records.delete(guildId);
                for (PlayerCharacterRecord member : characters.findByGuild(guildId)) {
                    persistMembership(member.getId(), 0, 5);
                }
                return head.orElse(null);
            }
            persistMembership(character.getId(), 0, 5);
            return null;
        }, disbanded -> {
            if (leader) {
                cache.remove(guildId);
                log.info(I18n.message("log.guild.disband"), guildId);
                character.setGuildId(0);
                character.setGuildRank(5);
                for (PacketSession online : sessions.all()) {
                    PlayerCharacter member = online.getAttr("character");
                    if (member == null || member.getGuildId() != guildId) continue;
                    member.setGuildId(0);
                    member.setGuildRank(5);
                    online.send(GuildPackets.disband(guildId));
                    online.send(GuildPackets.showGuildInfo(null));
                }
                broadcastPresence(character, false);
                return;
            }
            character.setGuildId(0);
            character.setGuildRank(5);
            session.send(GuildPackets.memberLeft(guildId, character.getId(), character.getName(), false));
            session.send(GuildPackets.showGuildInfo(null));
            log.info(I18n.message("log.guild.leave"), character.getId(), guildId);
            // 广播必须在清 guildId 之前：broadcastPresence 依赖 character.getGuildId() 找同会成员，
            // 先清零会让同公会成员永远收不到这条离线通知。
            broadcastPresenceOf(guildId, character);
        });
    }

    /** 用指定公会 id 广播某个角色的上下线，供退出这类"先清归属再广播"的场景使用。 */
    private void broadcastPresenceOf(int guildId, PlayerCharacter character) {
        if (guildId <= 0) return;
        for (PacketSession other : sessions.all()) {
            PlayerCharacter member = other.getAttr("character");
            if (member == null || member.getId() == character.getId()) continue;
            if (member.getGuildId() != guildId) continue;
            other.send(GuildPackets.memberOnline(guildId, character.getId(), false));
        }
    }

    /** 改公告：仅会长与副会级（职级 ≤2），长度上限 100。 */
    public void notice(PacketSession session, PlayerCharacter character, String text) {
        int guildId = character.getGuildId();
        if (guildId <= 0) {
            session.send(GuildPackets.genericMessage(GuildPackets.CODE_NOT_IN_GUILD));
            return;
        }
        if (character.getGuildRank() > 2) return;
        String notice = text == null ? "" : text.trim();
        if (notice.getBytes(InPacket.DEFAULT_CHARSET).length > MAX_NOTICE_BYTES) return;
        offload(() -> {
            GuildRecordEntity head = cache.get(guildId);
            if (head == null) head = records.findById(guildId).orElse(null);
            if (head == null) return null;
            int expected = head.getRowVersion();
            head.setNotice(notice);
            if (!records.update(head, expected)) {
                // 另一个频道已经写过：丢弃缓存让它下次重读，不回滚内存态。
                log.warn(I18n.message("log.guild.version_conflict"), guildId, expected);
                return null;
            }
            head.setRowVersion(expected + 1);
            return head;
        }, head -> {
            if (head == null) return;
            cache.put(guildId, head);
            for (PacketSession online : sessions.all()) {
                PlayerCharacter member = online.getAttr("character");
                if (member != null && member.getGuildId() == guildId) {
                    online.send(GuildPackets.notice(guildId, head.getNotice()));
                }
            }
        });
    }

    /** 拒绝邀请：清掉自己的邀请并回执邀请者（结果码 55）。 */
    public void denyInvitation(PacketSession session, PlayerCharacter character) {
        Invite invite = session.getAttr(INVITE_ATTR);
        session.setAttr(INVITE_ATTR, null);
        if (invite == null) return;
        PacketSession sender = findSession(invite.senderName());
        if (sender != null) {
            sender.send(GuildPackets.responseMessage(GuildPackets.CODE_DECLINED_INVITE, character.getName()));
        }
    }

    /** 断线：向同频道在线成员广播离线标志，并清掉挂在自己身上的邀请。 */
    public void offline(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        session.setAttr(INVITE_ATTR, null);
        if (character != null) broadcastPresence(character, false);
    }

    /** 重建某会话的公会整表。 */
    public void refresh(PacketSession session, PlayerCharacter character) {
        int guildId = character.getGuildId();
        if (guildId <= 0) return;
        offload(() -> {
            GuildRecordEntity head = cache.get(guildId);
            if (head == null) head = records.findById(guildId).orElse(null);
            if (head == null) return null;
            List<GuildPackets.GuildMemberView> members = new ArrayList<>();
            for (PlayerCharacterRecord record : characters.findByGuild(guildId)) {
                members.add(new GuildPackets.GuildMemberView(record.getId(), record.getName(),
                        record.getJob(), record.getLevel(), record.getGuildRank(),
                        online(record.getId())));
            }
            return new Snapshot(head, members);
        }, snapshot -> {
            if (snapshot == null) {
                // 公会头已被删（例如会长解散）：清空客户端窗口，而不是把旧数据留在界面上。
                session.send(GuildPackets.showGuildInfo(null));
                return;
            }
            cache.put(guildId, snapshot.head());
            pushInfo(session, snapshot.head(), snapshot.members());
        });
    }

    private record Snapshot(GuildRecordEntity head, List<GuildPackets.GuildMemberView> members) { }

    /** 让某公会的所有本频道在线成员重建整表。 */
    private void refreshAllMembers(int guildId, GuildRecordEntity head) {
        for (PacketSession online : sessions.all()) {
            PlayerCharacter member = online.getAttr("character");
            if (member == null || member.getGuildId() != guildId) continue;
            refresh(online, member);
        }
    }

    private void pushInfo(PacketSession session, GuildRecordEntity head, List<GuildPackets.GuildMemberView> members) {
        GuildPackets.GuildView view = new GuildPackets.GuildView(
                head.getGuildRecordId(), head.getName(), rankTitles(head), head.getCapacity(), head.getPointGp(),
                head.getLogoBackground(), head.getLogoBackgroundColor(), head.getLogoMark(), head.getLogoMarkColor(),
                head.getNotice(), head.getAllianceId(), members);
        session.send(GuildPackets.showGuildInfo(view));
    }

    private static List<String> rankTitles(GuildRecordEntity head) {
        return List.of(head.getRankTitle1(), head.getRankTitle2(), head.getRankTitle3(),
                head.getRankTitle4(), head.getRankTitle5());
    }

    private static GuildPackets.GuildMemberView memberView(PlayerCharacter character, int rank) {
        return new GuildPackets.GuildMemberView(character.getId(), character.getName(),
                character.getJob(), character.getLevel(), rank, true);
    }

    /** 上下线广播：只发给同频道里同公会的在线成员。 */
    private void broadcastPresence(PlayerCharacter character, boolean online) {
        int guildId = character.getGuildId();
        if (guildId <= 0) return;
        for (PacketSession other : sessions.all()) {
            PlayerCharacter member = other.getAttr("character");
            if (member == null || member.getId() == character.getId()) continue;
            if (member.getGuildId() != guildId) continue;
            other.send(GuildPackets.memberOnline(guildId, character.getId(), online));
        }
    }

    private boolean online(long characterId) {
        if (sessions.get(characterId) != null) return true;
        if (intercoord == null) return false;
        try {
            Optional<PlayerPresence> presence = intercoord.presence(characterId);
            return presence != null && presence.isPresent();
        } catch (RuntimeException error) {
            log.debug(I18n.message("log.guild.presence_failed"), characterId, error);
            return false;
        }
    }

    private PacketSession findSession(String name) {
        if (name == null || name.isBlank()) return null;
        for (PacketSession candidate : sessions.all()) {
            PlayerCharacter character = candidate.getAttr("character");
            if (character != null && character.getName().equals(name)) return candidate;
        }
        return null;
    }

    /**
     * 写回角色行的公会归属。
     *
     * <p>整行 {@code save} 会连带给其他字段，与频道存档并发时可能互相覆盖；这是本期已知边界，
     * 后续应替换为只更新 {@code guild_id}/{@code guild_rank} 的仓储方法。
     */
    private void persistMembership(long characterId, int guildId, int rank) {
        Optional<PlayerCharacterRecord> record = characters.findById(characterId);
        if (record.isEmpty()) return;
        PlayerCharacterRecord row = record.get();
        row.setGuildId(guildId);
        row.setGuildRank(rank);
        characters.save(row);
    }

    private static boolean validName(String name) {
        if (name.isBlank() || name.length() < 2) return false;
        if (name.getBytes(org.gms.net.packet.InPacket.DEFAULT_CHARSET).length > MAX_NAME_BYTES) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean allowed = Character.isLetterOrDigit(ch) || ch == '_';
            if (!allowed) return false;
        }
        return true;
    }

    /** 有执行归属时走后台 IO + 版本门回包；否则同步执行（测试/无频道适配器）。 */
    private <T> void offload(Supplier<T> task, Consumer<T> completion) {
        if (io == null || sessions.execution() == null) {
            completion.accept(task.get());
            return;
        }
        long version = sessions.execution().version();
        io.submit(task).whenComplete((result, error) -> sessions.execution().continueAt(version, () -> {
            if (error != null) {
                log.error(I18n.message("log.social.io_failed"), error);
                return;
            }
            completion.accept(result);
        }));
    }
}
