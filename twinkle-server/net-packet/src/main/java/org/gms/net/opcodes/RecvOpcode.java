package org.gms.net.opcodes;

/**
 * v83 客户端→服务端操作码（字节级兼容，红线 1）。
 *
 * <p>每个值对应 v83 客户端「请求」侧的一个包类型，写包时取低 16 位小端。
 * 值来自公开 v83 协议规范，是协议事实常量，**不得改动**。
 *
 * <p>按功能块分组：登录 / 角色 / 世界 / 游戏动作 / 频道内 / 管理。M1 聚焦登录块
 * （握手 → 登录 → 服务器列表 → 角色列表 → 选角）。
 */
public enum RecvOpcode implements Opcode {

    /* ---------- 登录 / 账号 ---------- */
    LOGIN_PASSWORD(0x01),             // 登录密码
    GUEST_LOGIN(0x02),                // 游客登录
    SERVERLIST_REREQUEST(0x04),       // 重请求服务器列表
    CHARLIST_REQUEST(0x05),           // 请求角色列表
    SERVERSTATUS_REQUEST(0x06),       // 请求服务器状态
    ACCEPT_TOS(0x07),                 // 接受服务条款
    SET_GENDER(0x08),                 // 设置性别
    AFTER_LOGIN(0x09),                // 登录后操作
    REGISTER_PIN(0x0A),               // 注册 PIN
    SERVERLIST_REQUEST(0x0B),         // 请求服务器列表
    PLAYER_DC(0x0C),                  // 玩家断开
    VIEW_ALL_CHAR(0x0D),              // 查看所有角色
    PICK_ALL_CHAR(0x0E),              // 选择所有角色
    NAME_TRANSFER(0x10),              // 名称转移
    WORLD_TRANSFER(0x12),             // 世界转移
    CHAR_SELECT(0x13),                // 选择角色
    PLAYER_LOGGEDIN(0x14),            // 玩家已登录（进游戏）
    CHECK_CHAR_NAME(0x15),            // 检查角色名
    CREATE_CHAR(0x16),                // 创建角色
    DELETE_CHAR(0x17),                // 删除角色
    PONG(0x18),                       // 心跳响应
    CLIENT_START_ERROR(0x19),         // 客户端启动错误上报
    CLIENT_ERROR(0x1A),               // 客户端错误上报
    STRANGE_DATA(0x1B),               // 异常数据
    RELOG(0x1C),                      // 重新登录
    REGISTER_PIC(0x1D),               // 注册 PIC
    CHAR_SELECT_WITH_PIC(0x1E),       // 带 PIC 选角
    VIEW_ALL_PIC_REGISTER(0x1F),      // 查看所有角色（注册 PIC）
    VIEW_ALL_WITH_PIC(0x20),          // 查看所有角色（带 PIC）

    /* ---------- 世界 / 地图切换 ---------- */
    CHANGE_MAP(0x26),                 // 更改地图
    CHANGE_CHANNEL(0x27),             // 更改频道
    ENTER_CASHSHOP(0x28),             // 进入现金商店

    /* ---------- 游戏动作 ---------- */
    MOVE_PLAYER(0x29),                // 移动玩家
    CANCEL_CHAIR(0x2A),               // 取消椅子
    USE_CHAIR(0x2B),                  // 使用椅子
    CLOSE_RANGE_ATTACK(0x2C),         // 近战攻击
    RANGED_ATTACK(0x2D),              // 远程攻击
    MAGIC_ATTACK(0x2E),               // 魔法攻击
    TOUCH_MONSTER_ATTACK(0x2F),       // 触碰怪物攻击
    TAKE_DAMAGE(0x30),                // 受到伤害
    GENERAL_CHAT(0x31),               // 普通聊天
    CLOSE_CHALKBOARD(0x32),           // 关闭黑板
    FACE_EXPRESSION(0x33),            // 表情
    USE_ITEMEFFECT(0x34),             // 使用物品效果
    USE_DEATHITEM(0x35),              // 使用死亡物品
    MOB_BANISH_PLAYER(0x38),          // 怪物驱逐玩家
    MONSTER_BOOK_COVER(0x39),         // 怪物图鉴封面
    NPC_TALK(0x3A),                   // NPC 对话
    REMOTE_STORE(0x3B),               // 远程商店
    NPC_TALK_MORE(0x3C),              // 继续 NPC 对话
    NPC_SHOP(0x3D),                   // NPC 商店
    STORAGE(0x3E),                    // 仓库
    HIRED_MERCHANT_REQUEST(0x3F),     // 雇佣商人请求
    FREDRICK_ACTION(0x40),            // Fredrick 操作
    DUEY_ACTION(0x41),                // Duey 操作
    OWL_ACTION(0x42),                 // Owl 操作
    OWL_WARP(0x43),                   // Owl 传送
    ADMIN_SHOP(0x44),                 // 管理员商店
    ITEM_SORT(0x45),                  // 整理物品
    ITEM_SORT2(0x46),                 // 整理物品 2
    ITEM_MOVE(0x47),                  // 移动物品
    USE_ITEM(0x48),                   // 使用物品
    CANCEL_ITEM_EFFECT(0x49),         // 取消物品效果
    USE_SUMMON_BAG(0x4B),             // 使用召唤袋
    PET_FOOD(0x4C),                   // 宠物食物
    USE_MOUNT_FOOD(0x4D),             // 使用坐骑食物
    SCRIPTED_ITEM(0x4E),              // 脚本物品
    USE_CASH_ITEM(0x4F),              // 使用现金物品
    USE_CATCH_ITEM(0x51),             // 使用捕捉物品
    USE_SKILL_BOOK(0x52),             // 使用技能书
    USE_TELEPORT_ROCK(0x54),          // 使用传送石
    USE_RETURN_SCROLL(0x55),          // 使用返回卷轴
    USE_UPGRADE_SCROLL(0x56),         // 使用升级卷轴
    DISTRIBUTE_AP(0x57),              // 分配 AP
    AUTO_DISTRIBUTE_AP(0x58),         // 自动分配 AP
    HEAL_OVER_TIME(0x59),             // 持续治疗
    DISTRIBUTE_SP(0x5A),              // 分配 SP
    SPECIAL_MOVE(0x5B),               // 特殊移动
    CANCEL_BUFF(0x5C),                // 取消增益
    SKILL_EFFECT(0x5D),               // 技能效果
    MESO_DROP(0x5E),                  // 金币掉落
    GIVE_FAME(0x5F),                  // 赠予声望
    CHAR_INFO_REQUEST(0x61),          // 请求角色信息
    SPAWN_PET(0x62),                  // 生成宠物
    CANCEL_DEBUFF(0x63),              // 取消减益
    CHANGE_MAP_SPECIAL(0x64),         // 特殊改图
    USE_INNER_PORTAL(0x65),           // 使用内部传送门
    TROCK_ADD_MAP(0x66),              // 添加传送岩地图
    REPORT(0x6A),                     // 举报
    QUEST_ACTION(0x6B),               // 任务操作
    GRENADE_EFFECT(0x6D),             // 手榴弹效果
    SKILL_MACRO(0x6E),                // 技能宏
    USE_ITEM_REWARD(0x70),            // 使用物品奖励
    MAKER_SKILL(0x71),                // 制作器技能
    USE_TREASUER_CHEST(0x73),         // 使用宝箱
    USE_REMOTE(0x74),                 // 使用遥控器
    WATER_OF_LIFE(0x75),              // 生命之水
    ADMIN_CHAT(0x76),                 // 管理员聊天
    MULTI_CHAT(0x77),                 // 多人聊天
    WHISPER(0x78),                    // 密语
    SPOUSE_CHAT(0x79),                // 配偶聊天
    MESSENGER(0x7A),                  // 消息传递
    PLAYER_INTERACTION(0x7B),         // 玩家互动
    PARTY_OPERATION(0x7C),            // 组队操作
    DENY_PARTY_REQUEST(0x7D),         // 拒绝组队请求
    GUILD_OPERATION(0x7E),            // 公会操作
    DENY_GUILD_REQUEST(0x7F),         // 拒绝公会请求
    ADMIN_COMMAND(0x80),              // 管理员命令
    ADMIN_LOG(0x81),                  // 管理员日志
    BUDDYLIST_MODIFY(0x82),           // 修改好友列表
    NOTE_ACTION(0x83),                // 笔记操作
    USE_DOOR(0x85),                   // 使用传送门
    CHANGE_KEYMAP(0x87),              // 更改键盘映射
    RPS_ACTION(0x88),                 // 石头剪刀布
    RING_ACTION(0x89),                // 戒指操作
    WEDDING_ACTION(0x8A),             // 结婚操作
    WEDDING_TALK(0x8B),               // 结婚对话
    WEDDING_TALK_MORE(0x8B),          // 继续结婚对话
    ALLIANCE_OPERATION(0x8F),         // 联盟操作
    DENY_ALLIANCE_REQUEST(0x90),      // 拒绝联盟请求
    OPEN_FAMILY_PEDIGREE(0x91),       // 打开家族谱系
    OPEN_FAMILY(0x92),                // 打开家族
    ADD_FAMILY(0x93),                 // 添加家族
    SEPARATE_FAMILY_BY_SENIOR(0x94),  // 长辈分离家族
    SEPARATE_FAMILY_BY_JUNIOR(0x95),  // 晚辈分离家族
    ACCEPT_FAMILY(0x96),              // 接受家族邀请
    USE_FAMILY(0x97),                 // 使用家族功能
    CHANGE_FAMILY_MESSAGE(0x98),      // 更改家族公告
    FAMILY_SUMMON_RESPONSE(0x99),     // 家族召唤响应
    BBS_OPERATION(0x9B),              // 论坛操作
    ENTER_MTS(0x9C),                  // 进入 MTS
    USE_SOLOMON_ITEM(0x9D),           // 使用所罗门物品
    USE_GACHA_EXP(0x9E),              // 使用扭蛋经验
    NEW_YEAR_CARD_REQUEST(0x9F),      // 新年贺卡
    CASHSHOP_SURPRISE(0xA1),          // 现金商店惊喜
    CLICK_GUIDE(0xA2),                // 点击引导
    ARAN_COMBO_COUNTER(0xA3),         // Aran 连击计数
    MOVE_PET(0xA7),                   // 移动宠物
    PET_CHAT(0xA8),                   // 宠物对话
    PET_COMMAND(0xA9),                // 宠物命令
    PET_LOOT(0xAA),                   // 宠物拾取
    PET_AUTO_POT(0xAB),               // 宠物自动药水
    PET_EXCLUDE_ITEMS(0xAC),          // 宠物排除物品
    MOVE_SUMMON(0xAF),                // 移动召唤兽
    SUMMON_ATTACK(0xB0),              // 召唤兽攻击
    DAMAGE_SUMMON(0xB1),              // 召唤兽受击
    BEHOLDER(0xB2),                   // Beholder
    MOVE_DRAGON(0xB5),                // 移动龙
    CHANGE_QUICKSLOT(0xB7),           // 更改快捷栏
    MOVE_LIFE(0xBC),                  // 移动生命体
    AUTO_AGGRO(0xBD),                 // 自动仇恨
    FIELD_DAMAGE_MOB(0xBF),           // 场景伤害怪物
    MOB_DAMAGE_MOB_FRIENDLY(0xC0),    // 怪物伤害友方
    MONSTER_BOMB(0xC1),               // 怪物炸弹
    MOB_DAMAGE_MOB(0xC2),             // 怪物伤害怪物
    NPC_ACTION(0xC5),                 // NPC 动作
    ITEM_PICKUP(0xCA),                // 捡起物品
    DAMAGE_REACTOR(0xCD),             // 反应堆受击
    TOUCHING_REACTOR(0xCE),           // 触碰反应堆
    PLAYER_MAP_TRANSFER(0xCF),        // 玩家地图转移
    SNOWBALL(0xD3),                   // 雪球
    LEFT_KNOCKBACK(0xD4),             // 左侧击退
    COCONUT(0xD5),                    // 椰子
    MATCH_TABLE(0xD6),                // 匹配表
    MONSTER_CARNIVAL(0xDA),           // 怪物嘉年华
    PARTY_SEARCH_REGISTER(0xDC),      // 注册组队搜索
    PARTY_SEARCH_START(0xDE),         // 开始组队搜索
    PARTY_SEARCH_UPDATE(0xDF),        // 更新组队搜索
    CHECK_CASH(0xE4),                 // 检查现金
    CASHSHOP_OPERATION(0xE5),         // 现金商店操作
    COUPON_CODE(0xE6),                // 优惠券代码
    OPEN_ITEMUI(0xEC),                // 打开物品界面
    CLOSE_ITEMUI(0xED),               // 关闭物品界面
    USE_ITEMUI(0xEE),                 // 使用物品界面
    MTS_OPERATION(0xFD),              // MTS 操作
    USE_MAPLELIFE(0x100),             // 使用 MapleLife
    USE_HAMMER(0x104),                // 使用锤子

    /* ---------- 自定义 / 调试 ---------- */
    CUSTOM_PACKET(0x3713),            // 自定义封包
    SET_HPMPALERT(0x1000),            // 设置 HP/MP 警报
    MAPLETV(0xFFFE);                  // MapleTV

    private final int code;

    RecvOpcode(int code) {
        this.code = code;
    }

    @Override
    public int getValue() {
        return code;
    }

    @Override
    public String getName() {
        return name();
    }
}
