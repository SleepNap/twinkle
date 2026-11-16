package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公会最小闭环（创建 / 邀请 / 接受 / 退出 / 公告 / 拒绝 / 整表）的协议与持久化回归。
 *
 * <p>覆盖范围与边界：
 *
 * <ul>
 *   <li>被测对象是 {@link GuildService}（业务规则）与 {@link GuildHandler}（收包解析），
 *       持久化用 {@link GuildFixtures} 的内存桩，不连数据库。</li>
 *   <li>物品系统用真实 {@code DefaultItemSystem}（依赖只有 {@code VersionGate} 与
 *       {@code GameDataProvider}）：建会扣费走的是真正的 {@code changeMeso}，不是假实现，
 *       这样「金币不足不扣费」「重名不扣费」才真的验到了货币路径。</li>
 *   <li>IO 队列内联执行（{@link GuildFixtures#inlineIo()}）：{@code offload} 在
 *       {@code sessions.execution() == null} 时同步执行，测试不起线程、不 sleep、不轮询。</li>
 *   <li>{@code intercoord} 传 {@code null}：{@code GuildService.online()} 对 null 有兜底，
 *       异频道成员按离线处理——本期不做跨频道成员刷新。</li>
 * </ul>
 *
 * <p>包结构约定：所有公会包都是 {@code 0x41 (GUILD_OPERATION)} + 子 mode，结果码包是
 * 子 mode {@code 0} + 一个字节码（{@code responseMessage} 再跟名字），因此断言必须先读 opcode 再读 mode。
 */
public class GuildGameplayTest {
    private static final long LEADER_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final int LEVEL = 20;
    /** 建会固定花费，与 {@link GuildService} 内部常量一致。 */
    private static final int CREATE_COST = 1_500_000;
    private static final int RICH_MESO = 2_000_000;
    private static final String GUILD_NAME = "Twinkle";
    private static final String NOTICE_TEXT = "Hello guild";

    /** 客户端包体首字节 mode（{@code RecvOpcode.GUILD_OPERATION}）。 */
    private static final int MODE_CREATE = 0x02;
    private static final int MODE_INVITE = 0x05;
    private static final int MODE_ACCEPT = 0x06;
    private static final int MODE_LEAVE = 0x07;
    private static final int MODE_NOTICE = 0x10;

    /** 服务端 0x41 子包 mode。 */
    private static final int SUB_RESULT = 0x00;
    private static final int SUB_INVITE = 0x05;
    private static final int SUB_INFO = 0x1A;
    private static final int SUB_MEMBER_LEFT = 0x2C;
    private static final int SUB_DISBAND = 0x32;
    private static final int SUB_MEMBER_ONLINE = 0x3D;
    private static final int SUB_NOTICE = 0x44;

    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final GuildFixtures.Guilds guilds = new GuildFixtures.Guilds();
    private final GuildFixtures.Characters characters = new GuildFixtures.Characters();
    private final GuildService guild = new GuildService(GuildFixtures.WORLD, guilds, characters,
            GuildFixtures.inlineIo(), sessions, null,
            new DefaultItemSystem(new DefaultVersionGate(), GameDataProvider.fixed(Map.of(), Map.of())));
    private final GuildHandler handler = new GuildHandler(guild);

    @Test public void createPersistsHeadAndMarksCreatorAsLeader() {
        var leader = joined(LEADER_ID, RICH_MESO);

        guild.create(leader, leader.character, GUILD_NAME);

        // 公会头落库：世界、名字、会长 id 都对上。
        assertThat(guilds.rows).hasSize(1);
        var head = guilds.byName(GUILD_NAME);
        int guildId = Math.toIntExact(head.getGuildRecordId());
        assertThat(head.getWorld()).isEqualTo(GuildFixtures.WORLD);
        assertThat(head.getLeaderId()).isEqualTo(LEADER_ID);
        assertThat(head.getCapacity()).isEqualTo(GuildFixtures.CAPACITY);
        // 成员关系以角色行为权威：创建者 guild_id 写入、职级 1。
        assertThat(characters.byId(LEADER_ID).getGuildId()).isEqualTo(guildId);
        assertThat(characters.byId(LEADER_ID).getGuildRank()).isOne();
        assertThat(leader.character.getGuildId()).isEqualTo(guildId);
        assertThat(leader.character.getGuildRank()).isOne();
        // 扣费走真 itemSystem。
        assertThat(leader.character.getMeso()).isEqualTo(RICH_MESO - CREATE_COST);

        var info = onlySubPacket(leader, SUB_INFO);
        assertThat(info.readByte()).isEqualTo((byte) 1);           // 1 = 已在公会
        assertThat(info.readInt()).isEqualTo(guildId);
    }

    @Test public void createRejectsWhenAlreadyInGuild() {
        var session = joined(LEADER_ID, RICH_MESO);
        session.character.setGuildId(7);

        handler.handle(session, body(packet -> packet.writeByte(MODE_CREATE).writeString(GUILD_NAME)));

        // 单字节结果码：0x41 + mode 0 + 33。
        assertThat(resultCode(session)).isEqualTo(GuildPackets.CODE_ALREADY_IN_GUILD);
        assertThat(guilds.rows).isEmpty();
        assertThat(characters.byId(LEADER_ID).getGuildId()).isZero();
        assertThat(session.character.getMeso()).isEqualTo(RICH_MESO);
    }

    @Test public void createRejectsDuplicateName() {
        var leader = joined(LEADER_ID, RICH_MESO);
        guilds.seed(GuildFixtures.head(GUILD_NAME, 99L));

        guild.create(leader, leader.character, GUILD_NAME);

        assertThat(resultCode(leader)).isEqualTo(GuildPackets.CODE_NAME_TAKEN);
        // 仓库不新增行、原行不被改写、金币不扣。
        assertThat(guilds.rows).hasSize(1);
        assertThat(guilds.byName(GUILD_NAME).getLeaderId()).isEqualTo(99L);
        assertThat(leader.character.getMeso()).isEqualTo(RICH_MESO);
        assertThat(leader.character.getGuildId()).isZero();
        assertThat(characters.byId(LEADER_ID).getGuildId()).isZero();
    }

    @Test public void createRejectsWhenMesoBelowCost() {
        var leader = joined(LEADER_ID, CREATE_COST - 1);

        guild.create(leader, leader.character, GUILD_NAME);

        assertThat(resultCode(leader)).isEqualTo(GuildPackets.CODE_CANNOT_CREATE);
        assertThat(leader.character.getMeso()).isEqualTo(CREATE_COST - 1);
        assertThat(guilds.rows).isEmpty();
        assertThat(characters.byId(LEADER_ID).getGuildId()).isZero();
    }

    @Test public void createRejectsInvalidName() {
        var leader = joined(LEADER_ID, RICH_MESO);

        guild.create(leader, leader.character, "a b");
        assertThat(resultCode(leader)).isEqualTo(GuildPackets.CODE_NAME_TAKEN);

        leader.sent.clear();
        guild.create(leader, leader.character, "");
        assertThat(resultCode(leader)).isEqualTo(GuildPackets.CODE_NAME_TAKEN);

        assertThat(guilds.rows).isEmpty();
        assertThat(leader.character.getMeso()).isEqualTo(RICH_MESO);
    }

    @Test public void inviteSendsMode5ToTargetInSameChannel() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var target = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        leader.sent.clear();
        target.sent.clear();

        handler.handle(leader, body(packet -> packet.writeByte(MODE_INVITE)
                .writeString(target.character.getName())));

        // 被邀请者收到 0x41/0x05 + 公会 id + 邀请者名。
        var invite = onlySubPacket(target, SUB_INVITE);
        assertThat(invite.readInt()).isEqualTo(guildId);
        assertThat(invite.readString()).isEqualTo(leader.character.getName());
        assertThat(invite.available()).isZero();
        // 邀请保存在目标会话内存里，落库无关。
        GuildService.Invite stored = target.getAttr(GuildService.INVITE_ATTR);
        assertThat(stored).isNotNull();
        assertThat(stored.guildId()).isEqualTo(guildId);
        assertThat(stored.senderName()).isEqualTo(leader.character.getName());

        // 邀请者收到结果包：码 0 + 目标名。
        var ack = onlySubPacket(leader, SUB_RESULT);
        assertThat(ack.readByte()).isZero();
        assertThat(ack.readString()).isEqualTo(target.character.getName());
        assertThat(ack.available()).isZero();
    }

    @Test public void inviteRejectsWhenRankTooLow() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var member = joined(MEMBER_ID, 0);
        var other = joined(3L, 0);
        final int guildId = createGuild(leader);
        // 公会头已在缓存与库里、成员也确实在这个公会：没发出邀请只可能是职级被拦。
        characters.byId(MEMBER_ID).setGuildId(guildId);
        member.character.setGuildId(guildId);
        member.character.setGuildRank(5);
        leader.sent.clear();
        member.sent.clear();
        other.sent.clear();

        handler.handle(member, body(packet -> packet.writeByte(MODE_INVITE)
                .writeString(other.character.getName())));

        assertThat(other.sent).isEmpty();
        assertThat(member.sent).isEmpty();
        Object invite = other.getAttr(GuildService.INVITE_ATTR);
        assertThat(invite).isNull();
    }

    @Test public void acceptJoinsWithRank5AndRefreshesMemberList() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var target = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        leader.sent.clear();
        target.sent.clear();

        handler.handle(leader, body(packet -> packet.writeByte(MODE_INVITE)
                .writeString(target.character.getName())));
        handler.handle(target, body(packet -> packet.writeByte(MODE_ACCEPT)
                .writeInt(guildId).writeInt((int) MEMBER_ID)));

        // 入会：职级 5，落库 + 内存态同步。
        assertThat(characters.byId(MEMBER_ID).getGuildId()).isEqualTo(guildId);
        assertThat(characters.byId(MEMBER_ID).getGuildRank()).isEqualTo(5);
        assertThat(target.character.getGuildId()).isEqualTo(guildId);
        assertThat(target.character.getGuildRank()).isEqualTo(5);
        Object consumed = target.getAttr(GuildService.INVITE_ATTR);
        assertThat(consumed).isNull();

        // 整表包：成员数 2（创建者 + 新成员）。
        assertThat(memberCount(onlySubPacket(target, SUB_INFO))).isEqualTo(2);
        assertThat(memberCount(onlySubPacket(leader, SUB_INFO))).isEqualTo(2);
        // 老成员收到新成员上线广播。
        var online = onlySubPacket(leader, SUB_MEMBER_ONLINE);
        assertThat(online.readInt()).isEqualTo(guildId);
        assertThat(online.readInt()).isEqualTo((int) MEMBER_ID);
        assertThat(online.readByte()).isEqualTo((byte) 1);
        assertThat(online.available()).isZero();
    }

    @Test public void acceptRejectsWrongCharacterId() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var target = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        leader.sent.clear();
        target.sent.clear();
        handler.handle(leader, body(packet -> packet.writeByte(MODE_INVITE)
                .writeString(target.character.getName())));
        int beforeAccept = target.sent.size();

        // cid 不是自己：整包丢弃，不入会、不发整表。
        handler.handle(target, body(packet -> packet.writeByte(MODE_ACCEPT)
                .writeInt(guildId).writeInt((int) LEADER_ID)));

        assertThat(target.sent).hasSize(beforeAccept);
        assertThat(subPackets(target, SUB_INFO)).isEmpty();
        assertThat(target.character.getGuildId()).isZero();
        assertThat(characters.byId(MEMBER_ID).getGuildId()).isZero();
        assertThat(characters.findByGuild(guildId)).hasSize(1);
    }

    @Test public void acceptRejectsExpiredOrMissingInvite() {
        var target = joined(MEMBER_ID, 0);

        // 没有邀请时的 mode 0x06：回 45 不在公会。
        handler.handle(target, body(packet -> packet.writeByte(MODE_ACCEPT)
                .writeInt(1).writeInt((int) MEMBER_ID)));

        assertThat(resultCode(target)).isEqualTo(GuildPackets.CODE_NOT_IN_GUILD);
        assertThat(target.character.getGuildId()).isZero();
        assertThat(characters.byId(MEMBER_ID).getGuildId()).isZero();
    }

    @Test public void memberListWritesFixed13ByteNameAndOnlineFlag() {
        var leader = joined(LEADER_ID, RICH_MESO);
        final int guildId = createGuild(leader);
        // 第二个成员只落库、没有本频道会话：整表必须写 online=0。
        characters.seed(MEMBER_ID, "Player2", 30, 100);
        characters.byId(MEMBER_ID).setGuildId(guildId);
        leader.sent.clear();

        guild.refresh(leader, leader.character);

        var wire = onlySubPacket(leader, SUB_INFO);
        assertThat(wire.readByte()).isEqualTo((byte) 1);                       // 1 = 已在公会
        assertThat(wire.readInt()).isEqualTo(guildId);
        assertThat(wire.readString()).isEqualTo(GUILD_NAME);
        // 五个职级称号走实体默认值（下标 0 对应职级 1）。
        assertThat(wire.readString()).isEqualTo("Master");
        assertThat(wire.readString()).isEqualTo("Jr. Master");
        assertThat(wire.readString()).isEqualTo("Member");
        assertThat(wire.readString()).isEqualTo("Member");
        assertThat(wire.readString()).isEqualTo("Member");
        // 成员 id 段（顺序 = characters.findByGuild 顺序）。
        assertThat(wire.readByte() & 0xFF).isEqualTo(2);
        assertThat(wire.readInt()).isEqualTo((int) LEADER_ID);
        assertThat(wire.readInt()).isEqualTo((int) MEMBER_ID);

        // 成员属性段：13 字节定长名字 + job/level/rank/online + 两个固定 0 字段。
        assertThat(readFixedName(wire)).isEqualTo(leader.character.getName());
        assertThat(wire.readInt()).isZero();                                   // job
        assertThat(wire.readInt()).isEqualTo(LEVEL);                           // level
        assertThat(wire.readInt()).isOne();                                    // rank
        assertThat(wire.readInt()).isOne();                                    // online
        assertThat(wire.readInt()).isZero();                                   // signature
        assertThat(wire.readInt()).isZero();                                   // allianceRank

        assertThat(readFixedName(wire)).isEqualTo("Player2");
        assertThat(wire.readInt()).isEqualTo(100);                             // job
        assertThat(wire.readInt()).isEqualTo(30);                              // level
        assertThat(wire.readInt()).isEqualTo(5);                               // rank
        assertThat(wire.readInt()).isZero();                                   // online = 0
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();

        // 尾部：capacity → 4 个徽章字段 → notice → GP → allianceId。
        assertThat(wire.readInt()).isEqualTo(GuildFixtures.CAPACITY);
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readString()).isEmpty();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();
    }

    @Test public void leaveClearsMembershipAndSendsMemberLeft() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var member = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        characters.byId(MEMBER_ID).setGuildId(guildId);
        characters.byId(MEMBER_ID).setGuildRank(5);
        member.character.setGuildId(guildId);
        member.character.setGuildRank(5);
        leader.sent.clear();
        member.sent.clear();

        handler.handle(member, body(packet -> packet.writeByte(MODE_LEAVE)
                .writeInt((int) MEMBER_ID).writeString(member.character.getName())));

        // 成员关系清空（内存态与角色行同时归零），职级回落默认 5。
        assertThat(member.character.getGuildId()).isZero();
        assertThat(characters.byId(MEMBER_ID).getGuildId()).isZero();
        assertThat(characters.byId(MEMBER_ID).getGuildRank()).isEqualTo(5);
        // 公会头不动。
        assertThat(guilds.byId(guildId).getLeaderId()).isEqualTo(LEADER_ID);

        var left = onlySubPacket(member, SUB_MEMBER_LEFT);
        assertThat(left.readInt()).isEqualTo(guildId);
        assertThat(left.readInt()).isEqualTo((int) MEMBER_ID);
        assertThat(left.readString()).isEqualTo(member.character.getName());
        assertThat(left.available()).isZero();

        // 紧随其后是「已不在公会」的单字节整表包。
        var cleared = onlySubPacket(member, SUB_INFO);
        assertThat(cleared.readByte()).isZero();
        assertThat(cleared.available()).isZero();
    }

    @Test public void leaveByLeaderDisbandsGuild() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var member = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        characters.byId(MEMBER_ID).setGuildId(guildId);
        member.character.setGuildId(guildId);
        member.character.setGuildRank(5);
        leader.sent.clear();
        member.sent.clear();

        handler.handle(leader, body(packet -> packet.writeByte(MODE_LEAVE)
                .writeInt((int) LEADER_ID).writeString(leader.character.getName())));

        // 解散：公会头删除，所有成员（含离线落库行）的 guild_id 归零。
        assertThat(guilds.rows).isEmpty();
        assertThat(characters.byId(MEMBER_ID).getGuildId()).isZero();
        assertThat(characters.byId(LEADER_ID).getGuildId()).isZero();
        assertThat(member.character.getGuildId()).isZero();
        assertThat(leader.character.getGuildId()).isZero();

        var disband = onlySubPacket(member, SUB_DISBAND);
        assertThat(disband.readInt()).isEqualTo(guildId);
        assertThat(disband.readByte()).isEqualTo((byte) 1);
        assertThat(disband.available()).isZero();
        assertThat(onlySubPacket(member, SUB_INFO).readByte()).isZero();
    }

    @Test public void noticeUpdatesHeadAndBroadcastsMode44() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var member = joined(MEMBER_ID, 0);
        final int guildId = createGuild(leader);
        characters.byId(MEMBER_ID).setGuildId(guildId);
        member.character.setGuildId(guildId);
        member.character.setGuildRank(5);
        leader.sent.clear();
        member.sent.clear();

        guild.notice(leader, leader.character, "  " + NOTICE_TEXT + "  ");

        // 落库：notice 去空白后写入，乐观锁版本 +1。
        assertThat(guilds.byId(guildId).getNotice()).isEqualTo(NOTICE_TEXT);
        assertThat(guilds.byId(guildId).getRowVersion()).isOne();

        // 在线同公会成员收到 0x41/0x44。
        for (var session : List.of(leader, member)) {
            var packet = onlySubPacket(session, SUB_NOTICE);
            assertThat(packet.readInt()).isEqualTo(guildId);
            assertThat(packet.readString()).isEqualTo(NOTICE_TEXT);
            assertThat(packet.available()).isZero();
        }
    }

    @Test public void noticeRejectsForRankAboveTwo() {
        var member = joined(MEMBER_ID, 0);
        guilds.seed(GuildFixtures.head(GUILD_NAME, LEADER_ID));
        final int guildId = Math.toIntExact(guilds.byName(GUILD_NAME).getGuildRecordId());
        characters.byId(MEMBER_ID).setGuildId(guildId);
        member.character.setGuildId(guildId);
        member.character.setGuildRank(5);

        guild.notice(member, member.character, NOTICE_TEXT);

        assertThat(member.sent).isEmpty();
        assertThat(guilds.byId(guildId).getNotice()).isEmpty();
        assertThat(guilds.byId(guildId).getRowVersion()).isZero();
    }

    @Test public void denyInvitationNotifiesSenderWithCode55() {
        var leader = joined(LEADER_ID, RICH_MESO);
        var target = joined(MEMBER_ID, 0);
        createGuild(leader);
        leader.sent.clear();
        target.sent.clear();
        handler.handle(leader, body(packet -> packet.writeByte(MODE_INVITE)
                .writeString(target.character.getName())));
        assertThat(subPackets(target, SUB_INVITE)).hasSize(1);

        // DENY_GUILD_REQUEST：包体是 byte + 字符串，byte 忽略。
        handler.deny(target, body(packet -> packet.writeByte(0)
                .writeString(leader.character.getName())));

        Object clearedInvite = target.getAttr(GuildService.INVITE_ATTR);
        assertThat(clearedInvite).isNull();
        // 邀请者先收到邀请成功（码 0），再收到拒绝（码 55）。
        assertThat(subPackets(leader, SUB_RESULT)).hasSize(2);
        var declined = lastSubPacket(leader, SUB_RESULT);
        assertThat(declined.readByte() & 0xFF).isEqualTo(GuildPackets.CODE_DECLINED_INVITE);
        assertThat(declined.readString()).isEqualTo(target.character.getName());
        assertThat(declined.available()).isZero();
        assertThat(target.character.getGuildId()).isZero();
    }

    @Test public void initializeSendsEmptyPacketWhenNotInGuild() {
        var session = joined(LEADER_ID, 0);

        guild.initialize(session);

        var wire = onlySubPacket(session, SUB_INFO);
        assertThat(wire.readByte()).isZero();
        assertThat(wire.available()).isZero();
    }

    @Test public void malformedShortPacketIsIgnored() {
        var leader = joined(LEADER_ID, RICH_MESO);

        // 只带 mode 字节：长度不足的三个 mode 一律静默丢弃，不抛异常、不发包。
        handler.handle(leader, body(packet -> packet.writeByte(MODE_CREATE)));
        handler.handle(leader, body(packet -> packet.writeByte(MODE_INVITE)));
        handler.handle(leader, body(packet -> packet.writeByte(MODE_ACCEPT).writeInt(1)));
        handler.handle(leader, body(packet -> packet.writeByte(MODE_LEAVE).writeInt(1)));
        handler.handle(leader, body(packet -> packet.writeByte(MODE_NOTICE)));
        // 空包与未知 mode 同样丢弃。
        handler.handle(leader, new ByteArrayInPacket(new byte[0]));
        handler.handle(leader, body(packet -> packet.writeByte(0x08).writeInt(1)));

        assertThat(leader.sent).isEmpty();
        assertThat(guilds.rows).isEmpty();
        assertThat(leader.character.getGuildId()).isZero();
        assertThat(characters.byId(LEADER_ID).getGuildId()).isZero();
    }

    /** 进图会话：等级够建会、金币由用例给、角色行已在库里（成员关系写回需要它）。 */
    private GameplayTestSession joined(long id, int meso) {
        var session = new GameplayTestSession(id, new MapleMap());
        session.character.setLevel(LEVEL);
        session.character.setMeso(meso);
        sessions.claim(id, session);
        characters.seed(id, session.character.getName(), LEVEL, 0);
        return session;
    }

    /** 建会并返回公会 id：前置条件不成立时在这里失败，后续用例不必重复断言。 */
    private int createGuild(GameplayTestSession leader) {
        guild.create(leader, leader.character, GUILD_NAME);
        int guildId = leader.character.getGuildId();
        assertThat(guildId).isPositive();
        assertThat(leader.character.getGuildRank()).isOne();
        assertThat(characters.byId(leader.character.getId()).getGuildId()).isEqualTo(guildId);
        return guildId;
    }

    /** 会话内匹配某子 mode 的全部 0x41 包（已读过 opcode 与 mode，reader 停在子包体首字节）。 */
    private static List<ByteArrayInPacket> subPackets(GameplayTestSession session, int subMode) {
        List<ByteArrayInPacket> matches = new ArrayList<>();
        for (OutPacket packet : session.sent) {
            var wire = new ByteArrayInPacket(packet.getBytes());
            if (wire.readUnsignedShort() != SendOpcode.GUILD_OPERATION.getValue()) {
                continue;
            }
            if ((wire.readByte() & 0xFF) != subMode) {
                continue;
            }
            matches.add(wire);
        }
        return matches;
    }

    /** 唯一匹配子包（顺带断言没有重复投递）。 */
    private static ByteArrayInPacket onlySubPacket(GameplayTestSession session, int subMode) {
        List<ByteArrayInPacket> matches = subPackets(session, subMode);
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    /** 最后一个匹配子包（同一 mode 会多次投递时用，例如邀请回执 + 拒绝回执）。 */
    private static ByteArrayInPacket lastSubPacket(GameplayTestSession session, int subMode) {
        List<ByteArrayInPacket> matches = subPackets(session, subMode);
        assertThat(matches).isNotEmpty();
        return matches.getLast();
    }

    /** 单字节结果码（码包 = 0x41 + mode 0 + 码）。 */
    private static int resultCode(GameplayTestSession session) {
        return onlySubPacket(session, SUB_RESULT).readByte() & 0xFF;
    }

    /** 整表包里的成员数：跳过「在会标志 + 公会 id + 公会名 + 5 个职级称号」。 */
    private static int memberCount(ByteArrayInPacket wire) {
        assertThat(wire.readByte()).isEqualTo((byte) 1);
        wire.readInt();
        wire.readString();
        for (int i = 0; i < 5; i++) {
            wire.readString();
        }
        return wire.readByte() & 0xFF;
    }

    /** 读 13 字节定长名字（GBK；去掉尾部 0 填充，与 {@code GuildPackets.writeFixedString} 对称）。 */
    private static String readFixedName(ByteArrayInPacket wire) {
        byte[] raw = wire.readBytes(GuildFixtures.NAME_LENGTH);
        int length = raw.length;
        while (length > 0 && raw[length - 1] == 0) {
            length--;
        }
        return new String(raw, 0, length, InPacket.DEFAULT_CHARSET);
    }

    /** 拼客户端收包包体（opcode 已由 HandlerRegistry 剥离，这里只写包体）。 */
    private static InPacket body(Consumer<ByteArrayOutPacket> writer) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        writer.accept(packet);
        return new ByteArrayInPacket(packet.getBytes());
    }
}
