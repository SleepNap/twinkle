package org.gms.net.opcodes;

/**
 * v83 服务端→客户端操作码（字节级兼容，红线 1）。
 *
 * <p>每个值对应服务端「响应」侧的一个包类型，写包时取低 16 位小端。
 * 值来自公开 v83 协议规范，是协议事实常量，**不得改动**。
 *
 * <p>按功能块分组：登录 / 服务器列表 / 角色 / 上下文 / 场景 / 战斗 / 地图对象 / 现金商店。
 * M1 聚焦登录块：LOGIN_STATUS、SERVERLIST、CHARLIST、SERVER_IP、SELECT_CHARACTER_BY_VAC 等。
 */
public enum SendOpcode implements Opcode {

    /* ---------- 登录 ---------- */
    LOGIN_STATUS(0x00),               // 登录结果
    GUEST_ID_LOGIN(0x01),             // 游客登录
    ACCOUNT_INFO(0x02),               // 账户信息
    SERVERSTATUS(0x03),               // 服务器状态（人数限制）
    GENDER_DONE(0x04),                // 性别设置结果
    CONFIRM_EULA_RESULT(0x05),        // EULA 确认结果
    CHECK_PINCODE(0x06),              // 检查 PIN
    UPDATE_PINCODE(0x07),             // 更新 PIN

    /* ---------- 服务器列表 / 角色 ---------- */
    VIEW_ALL_CHAR(0x08),              // 查看所有角色
    SELECT_CHARACTER_BY_VAC(0x09),    // 选角结果（进服务器）
    SERVERLIST(0x0A),                 // 服务器列表
    CHARLIST(0x0B),                   // 角色列表
    SERVER_IP(0x0C),                  // 服务器 IP（进频道）
    CHAR_NAME_RESPONSE(0x0D),         // 角色名检查结果
    ADD_NEW_CHAR_ENTRY(0x0E),         // 添加新角色条目
    DELETE_CHAR_RESPONSE(0x0F),       // 删除角色结果
    CHANGE_CHANNEL(0x10),             // 更改频道
    PING(0x11),                       // 心跳
    KOREAN_INTERNET_CAFE_SHIT(0x12),  // 韩国网吧标记（忽略）
    CHANNEL_SELECTED(0x14),           // 已选频道
    HACKSHIELD_REQUEST(0x15),         // HackShield 请求
    RELOG_RESPONSE(0x16),             // 重新登录响应
    CHECK_CRC_RESULT(0x19),           // CRC 校验结果
    LAST_CONNECTED_WORLD(0x1A),       // 上次连接的世界
    RECOMMENDED_WORLD_MESSAGE(0x1B),  // 推荐世界消息
    CHECK_SPW_RESULT(0x1C),           // SPW 校验结果

    /* ---------- 上下文（CWvsContext） ---------- */
    INVENTORY_OPERATION(0x1D),        // 物品栏操作
    INVENTORY_GROW(0x1E),             // 扩展物品栏
    STAT_CHANGED(0x1F),               // 状态变化
    GIVE_BUFF(0x20),                  // 施加增益
    CANCEL_BUFF(0x21),                // 移除增益
    FORCED_STAT_SET(0x22),            // 强制设置状态
    FORCED_STAT_RESET(0x23),          // 强制重置状态
    UPDATE_SKILLS(0x24),              // 更新技能
    SKILL_USE_RESULT(0x25),           // 技能使用结果
    FAME_RESPONSE(0x26),              // 声望响应
    SHOW_STATUS_INFO(0x27),           // 显示状态信息
    OPEN_FULL_CLIENT_DOWNLOAD_LINK(0x28), // 完整客户端下载链接
    MEMO_RESULT(0x29),                // 备忘录结果
    MAP_TRANSFER_RESULT(0x2A),        // 地图转移结果
    WEDDING_PHOTO(0x2B),              // 结婚照片
    CLAIM_RESULT(0x2D),               // 领取结果
    CLAIM_AVAILABLE_TIME(0x2E),       // 领取可用时间
    CLAIM_STATUS_CHANGED(0x2F),       // 领取状态变化
    SET_TAMING_MOB_INFO(0x30),        // 设置驯服怪物信息
    QUEST_CLEAR(0x31),                // 任务完成
    ENTRUSTED_SHOP_CHECK_RESULT(0x32),// 委托商店检查结果
    SKILL_LEARN_ITEM_RESULT(0x33),    // 学习技能物品结果
    GATHER_ITEM_RESULT(0x34),         // 收集物品结果
    SORT_ITEM_RESULT(0x35),           // 整理物品结果
    SUE_CHARACTER_RESULT(0x37),       // 控诉角色结果
    TRADE_MONEY_LIMIT(0x39),          // 交易金钱上限
    SET_GENDER(0x3A),                 // 设置性别
    GUILD_BBS_PACKET(0x3B),           // 公会公告板
    CHAR_INFO(0x3D),                  // 角色信息
    PARTY_OPERATION(0x3E),            // 组队操作
    BUDDYLIST(0x3F),                  // 好友列表
    GUILD_OPERATION(0x41),            // 公会操作
    ALLIANCE_OPERATION(0x42),         // 联盟操作
    SPAWN_PORTAL(0x43),               // 生成传送门
    SERVERMESSAGE(0x44),              // 服务器消息
    INCUBATOR_RESULT(0x45),           // 孵化器结果
    SHOP_SCANNER_RESULT(0x46),        // 商店扫描结果
    SHOP_LINK_RESULT(0x47),           // 商店链接结果
    MARRIAGE_REQUEST(0x48),           // 结婚请求
    MARRIAGE_RESULT(0x49),            // 结婚结果
    WEDDING_GIFT_RESULT(0x4A),        // 结婚礼物结果
    NOTIFY_MARRIED_PARTNER_MAP_TRANSFER(0x4B), // 通知配偶地图转移
    CASH_PET_FOOD_RESULT(0x4C),       // 宠物食物结果
    SET_WEEK_EVENT_MESSAGE(0x4D),     // 设置周活动消息
    SET_POTION_DISCOUNT_RATE(0x4E),   // 设置药水折扣率
    BRIDLE_MOB_CATCH_FAIL(0x4F),      // 捕捉怪物失败
    IMITATED_NPC_RESULT(0x50),        // 仿冒 NPC 结果
    IMITATED_NPC_DATA(0x51),          // 仿冒 NPC 数据
    LIMITED_NPC_DISABLE_INFO(0x52),   // 限时 NPC 禁用信息
    MONSTER_BOOK_SET_CARD(0x53),      // 怪物图鉴卡片
    MONSTER_BOOK_SET_COVER(0x54),     // 怪物图鉴封面
    HOUR_CHANGED(0x55),               // 时间变化
    MINIMAP_ON_OFF(0x56),             // 小地图开关
    CONSULT_AUTHKEY_UPDATE(0x57),     // 咨询认证密钥更新
    CLASS_COMPETITION_AUTHKEY_UPDATE(0x58), // 竞技场认证密钥更新
    WEB_BOARD_AUTHKEY_UPDATE(0x59),   // 论坛认证密钥更新
    SESSION_VALUE(0x5A),              // 会话值
    PARTY_VALUE(0x5B),                // 组队值
    FIELD_SET_VARIABLE(0x5C),         // 字段变量
    BONUS_EXP_CHANGED(0x5D),          // 奖励经验变化
    FAMILY_CHART_RESULT(0x5E),        // 家族图表结果
    FAMILY_INFO_RESULT(0x5F),         // 家族信息结果
    FAMILY_RESULT(0x60),              // 家族结果
    FAMILY_JOIN_REQUEST(0x61),        // 家族加入请求
    FAMILY_JOIN_REQUEST_RESULT(0x62), // 家族加入请求结果
    FAMILY_JOIN_ACCEPTED(0x63),       // 家族加入已接受
    FAMILY_PRIVILEGE_LIST(0x64),      // 家族权限列表
    FAMILY_REP_GAIN(0x65),            // 家族声望获得
    FAMILY_NOTIFY_LOGIN_OR_LOGOUT(0x66), // 家族成员登录/登出通知
    FAMILY_SET_PRIVILEGE(0x67),       // 设置家族权限
    FAMILY_SUMMON_REQUEST(0x68),      // 家族召唤请求
    NOTIFY_LEVELUP(0x69),             // 升级通知
    NOTIFY_MARRIAGE(0x6A),            // 结婚通知
    NOTIFY_JOB_CHANGE(0x6B),          // 职业变更通知
    MAPLE_TV_USE_RES(0x6D),           // MapleTV 使用结果
    AVATAR_MEGAPHONE_RESULT(0x6E),    // 头像喇叭结果
    SET_AVATAR_MEGAPHONE(0x6F),       // 设置头像喇叭
    CLEAR_AVATAR_MEGAPHONE(0x70),     // 清除头像喇叭
    CANCEL_NAME_CHANGE_RESULT(0x71),  // 取消改名结果
    CANCEL_TRANSFER_WORLD_RESULT(0x72), // 取消转服结果
    DESTROY_SHOP_RESULT(0x73),        // 销毁商店结果
    FAKE_GM_NOTICE(0x74),             // 假 GM 通知
    SUCCESS_IN_USE_GACHAPON_BOX(0x75),// 成功使用扭蛋箱
    NEW_YEAR_CARD_RES(0x76),          // 新年贺卡结果
    RANDOM_MORPH_RES(0x77),           // 随机变身结果
    CANCEL_NAME_CHANGE_BY_OTHER(0x78),// 他人取消改名
    SET_EXTRA_PENDANT_SLOT(0x79),     // 设置额外饰品插槽
    SCRIPT_PROGRESS_MESSAGE(0x7A),    // 脚本进度消息
    DATA_CRC_CHECK_FAILED(0x7B),      // 数据 CRC 校验失败
    MACRO_SYS_DATA_INIT(0x7C),        // 宏系统数据初始化

    /* ---------- 场景（CStage / CField） ---------- */
    SET_FIELD(0x7D),                  // 设置字段（进图）
    SET_ITC(0x7E),                    // 设置 ITC
    SET_CASH_SHOP(0x7F),              // 设置现金商店
    SET_BACK_EFFECT(0x80),            // 背景特效
    SET_MAP_OBJECT_VISIBLE(0x81),     // 地图对象可见性
    CLEAR_BACK_EFFECT(0x82),          // 清除背景特效
    BLOCKED_MAP(0x83),                // 被阻止的地图
    BLOCKED_SERVER(0x84),             // 被阻止的服务器
    FORCED_MAP_EQUIP(0x85),           // 强制地图装备
    MULTICHAT(0x86),                  // 多人聊天
    WHISPER(0x87),                    // 密语
    SPOUSE_CHAT(0x88),                // 配偶聊天
    SUMMON_ITEM_INAVAILABLE(0x89),    // 召唤物品不可用
    FIELD_EFFECT(0x8A),               // 场景效果
    FIELD_OBSTACLE_ONOFF(0x8B),       // 场景障碍物开关
    FIELD_OBSTACLE_ONOFF_LIST(0x8C),  // 场景障碍物开关列表
    FIELD_OBSTACLE_ALL_RESET(0x8D),   // 重置所有场景障碍物
    BLOW_WEATHER(0x8E),               // 天气效果
    PLAY_JUKEBOX(0x8F),               // 点唱机
    ADMIN_RESULT(0x90),               // 管理员结果
    OX_QUIZ(0x91),                    // OX 问答
    GMEVENT_INSTRUCTIONS(0x92),       // 游戏事件说明
    CLOCK(0x93),                      // 时钟
    CONTI_MOVE(0x94),                 // 连续移动
    CONTI_STATE(0x95),                // 连续状态
    SET_QUEST_CLEAR(0x96),            // 设置任务完成
    SET_QUEST_TIME(0x97),             // 设置任务时间
    ARIANT_RESULT(0x98),              // ARIANT 结果
    SET_OBJECT_STATE(0x99),           // 设置物体状态
    STOP_CLOCK(0x9A),                 // 停止时钟
    ARIANT_ARENA_SHOW_RESULT(0x9B),   // ARIANT 竞技场显示结果
    PYRAMID_GAUGE(0x9D),              // 金字塔计数器
    PYRAMID_SCORE(0x9E),              // 金字塔分数
    QUICKSLOT_INIT(0x9F),             // 快捷栏初始化
    SPAWN_PLAYER(0xA0),               // 生成玩家
    REMOVE_PLAYER_FROM_MAP(0xA1),     // 从地图移除玩家
    CHATTEXT(0xA2),                   // 聊天文本
    CHATTEXT1(0xA3),                  // 聊天文本 1
    CHALKBOARD(0xA4),                 // 黑板
    UPDATE_CHAR_BOX(0xA5),            // 更新角色盒子
    SHOW_CONSUME_EFFECT(0xA6),        // 消耗效果
    SHOW_SCROLL_EFFECT(0xA7),         // 卷轴效果
    SPAWN_PET(0xA8),                  // 生成宠物
    MOVE_PET(0xAA),                   // 移动宠物
    PET_CHAT(0xAB),                   // 宠物对话
    PET_NAMECHANGE(0xAC),             // 更改宠物名字
    PET_EXCEPTION_LIST(0xAD),         // 宠物异常列表
    PET_COMMAND(0xAE),                // 宠物命令
    SPAWN_SPECIAL_MAPOBJECT(0xAF),    // 生成特殊地图对象
    REMOVE_SPECIAL_MAPOBJECT(0xB0),   // 移除特殊地图对象
    MOVE_SUMMON(0xB1),                // 移动召唤兽
    SUMMON_ATTACK(0xB2),              // 召唤兽攻击
    DAMAGE_SUMMON(0xB3),              // 召唤兽受击
    SUMMON_SKILL(0xB4),               // 召唤兽技能
    SPAWN_DRAGON(0xB5),               // 生成龙
    MOVE_DRAGON(0xB6),                // 移动龙
    REMOVE_DRAGON(0xB7),              // 移除龙
    MOVE_PLAYER(0xB9),                // 移动玩家
    CLOSE_RANGE_ATTACK(0xBA),         // 近战攻击
    RANGED_ATTACK(0xBB),              // 远程攻击
    MAGIC_ATTACK(0xBC),               // 魔法攻击
    ENERGY_ATTACK(0xBD),              // 能量攻击
    SKILL_EFFECT(0xBE),               // 技能效果
    CANCEL_SKILL_EFFECT(0xBF),        // 取消技能效果
    DAMAGE_PLAYER(0xC0),              // 玩家受击
    FACIAL_EXPRESSION(0xC1),          // 表情
    SHOW_ITEM_EFFECT(0xC2),           // 物品效果
    SHOW_CHAIR(0xC4),                 // 显示椅子
    UPDATE_CHAR_LOOK(0xC5),           // 更新角色外观
    SHOW_FOREIGN_EFFECT(0xC6),        // 远程效果
    GIVE_FOREIGN_BUFF(0xC7),          // 远程增益
    CANCEL_FOREIGN_BUFF(0xC8),        // 取消远程增益
    UPDATE_PARTYMEMBER_HP(0xC9),      // 更新组队成员 HP
    GUILD_NAME_CHANGED(0xCA),         // 公会名改变
    GUILD_MARK_CHANGED(0xCB),         // 公会标志改变
    THROW_GRENADE(0xCC),              // 抛掷手榴弹
    CANCEL_CHAIR(0xCD),               // 取消椅子
    SHOW_ITEM_GAIN_INCHAT(0xCE),      // 聊天中显示获得物品
    DOJO_WARP_UP(0xCF),               // 武道馆传送准备
    LUCKSACK_PASS(0xD0),              // 幸运袋成功
    LUCKSACK_FAIL(0xD1),              // 幸运袋失败
    MESO_BAG_MESSAGE(0xD2),           // 金币背包消息
    UPDATE_QUEST_INFO(0xD3),          // 更新任务信息
    ON_NOTIFY_HP_DEC_BY_FIELD(0xD4),  // 字段 HP 减少通知
    PLAYER_HINT(0xD6),                // 玩家提示
    MAKER_RESULT(0xD9),               // 制作器结果
    KOREAN_EVENT(0xDB),               // 韩国活动
    OPEN_UI(0xDC),                    // 打开 UI
    LOCK_UI(0xDD),                    // 锁定 UI
    DISABLE_UI(0xDE),                 // 禁用 UI
    SPAWN_GUIDE(0xDF),                // 生成引导者
    TALK_GUIDE(0xE0),                 // 引导者对话
    SHOW_COMBO(0xE1),                 // 显示连击
    COOLDOWN(0xEA),                   // 冷却时间

    /* ---------- 地图对象 ---------- */
    SPAWN_MONSTER(0xEC),              // 生成怪物
    KILL_MONSTER(0xED),               // 击杀怪物
    SPAWN_MONSTER_CONTROL(0xEE),      // 控制生成怪物
    MOVE_MONSTER(0xEF),               // 移动怪物
    MOVE_MONSTER_RESPONSE(0xF0),      // 移动怪物响应
    APPLY_MONSTER_STATUS(0xF2),       // 应用怪物状态
    CANCEL_MONSTER_STATUS(0xF3),      // 取消怪物状态
    RESET_MONSTER_ANIMATION(0xF4),    // 重置怪物动画
    DAMAGE_MONSTER(0xF6),             // 怪物受击
    ARIANT_THING(0xF9),               // ARIANT 操作
    SHOW_MONSTER_HP(0xFA),            // 显示怪物 HP
    CATCH_MONSTER(0xFB),              // 捕捉怪物
    CATCH_MONSTER_WITH_ITEM(0xFC),    // 用物品捕捉怪物
    SHOW_MAGNET(0xFD),                // 显示磁铁
    SPAWN_NPC(0x101),                 // 生成 NPC
    REMOVE_NPC(0x102),                // 移除 NPC
    SPAWN_NPC_REQUEST_CONTROLLER(0x103), // 请求 NPC 控制
    NPC_ACTION(0x104),                // NPC 动作
    SET_NPC_SCRIPTABLE(0x107),        // 设置 NPC 可脚本化
    SPAWN_HIRED_MERCHANT(0x109),      // 生成雇佣商人
    DESTROY_HIRED_MERCHANT(0x10A),    // 销毁雇佣商人
    UPDATE_HIRED_MERCHANT(0x10B),     // 更新雇佣商人
    DROP_ITEM_FROM_MAPOBJECT(0x10C),  // 地图对象掉落物品
    REMOVE_ITEM_FROM_MAP(0x10D),      // 从地图移除物品
    CANNOT_SPAWN_KITE(0x10E),         // 无法生成风筝
    SPAWN_KITE(0x10F),                // 生成风筝
    REMOVE_KITE(0x110),               // 移除风筝
    SPAWN_MIST(0x111),                // 生成迷雾
    REMOVE_MIST(0x112),               // 移除迷雾
    SPAWN_DOOR(0x113),                // 生成门
    REMOVE_DOOR(0x114),               // 移除门
    REACTOR_HIT(0x115),               // 反应堆受击
    REACTOR_SPAWN(0x117),             // 生成反应堆
    REACTOR_DESTROY(0x118),           // 销毁反应堆
    SNOWBALL_STATE(0x119),            // 雪球状态
    HIT_SNOWBALL(0x11A),              // 击中雪球
    SNOWBALL_MESSAGE(0x11B),          // 雪球消息
    LEFT_KNOCK_BACK(0x11C),           // 左侧击退
    COCONUT_HIT(0x11D),               // 击中椰子
    COCONUT_SCORE(0x11E),             // 椰子得分
    GUILD_BOSS_HEALER_MOVE(0x11F),    // 公会长老移动
    GUILD_BOSS_PULLEY_STATE_CHANGE(0x120), // 公会长老滑轮状态
    MONSTER_CARNIVAL_START(0x121),    // 怪物嘉年华开始
    MONSTER_CARNIVAL_OBTAINED_CP(0x122), // 获得嘉年华 CP
    MONSTER_CARNIVAL_PARTY_CP(0x123), // 嘉年华队伍 CP
    MONSTER_CARNIVAL_SUMMON(0x124),   // 嘉年华召唤
    MONSTER_CARNIVAL_MESSAGE(0x125),  // 嘉年华消息
    MONSTER_CARNIVAL_DIED(0x126),     // 嘉年华死亡
    MONSTER_CARNIVAL_LEAVE(0x127),    // 离开嘉年华
    ARIANT_ARENA_USER_SCORE(0x129),   // 竞技场用户得分
    SHEEP_RANCH_INFO(0x12B),          // 羊牧场信息
    SHEEP_RANCH_CLOTHES(0x12C),       // 羊牧场服装
    WITCH_TOWER_SCORE_UPDATE(0x12D),  // 巫师塔得分更新
    HORNTAIL_CAVE(0x12E),             // 角龙头洞
    ZAKUM_SHRINE(0x12F),              // 扎昆神殿
    NPC_TALK(0x130),                  // NPC 对话
    OPEN_NPC_SHOP(0x131),             // 打开 NPC 商店
    CONFIRM_SHOP_TRANSACTION(0x132),  // 确认商店交易
    ADMIN_SHOP_MESSAGE(0x133),        // 管理员商店消息
    ADMIN_SHOP(0x134),                // 管理员商店
    STORAGE(0x135),                   // 仓库
    FREDRICK_MESSAGE(0x136),          // Fredrick 消息
    FREDRICK(0x137),                  // Fredrick
    RPS_GAME(0x138),                  // 石头剪刀布
    MESSENGER(0x139),                 // 消息传递
    PLAYER_INTERACTION(0x13A),        // 玩家互动
    TOURNAMENT(0x13B),                // 锦标赛
    TOURNAMENT_MATCH_TABLE(0x13C),    // 锦标赛匹配表
    TOURNAMENT_SET_PRIZE(0x13D),      // 设置锦标赛奖品
    TOURNAMENT_UEW(0x13E),            // 锦标赛功能
    TOURNAMENT_CHARACTERS(0x13F),     // 锦标赛角色
    WEDDING_PROGRESS(0x140),          // 结婚进度
    WEDDING_CEREMONY_END(0x141),      // 结婚仪式结束
    PARCEL(0x142),                    // 礼包
    CHARGE_PARAM_RESULT(0x143),       // 充值参数结果
    QUERY_CASH_RESULT(0x144),         // 查询现金结果
    CASHSHOP_OPERATION(0x145),        // 现金商店操作
    CASHSHOP_PURCHASE_EXP_CHANGED(0x146), // 现金购买经验变化
    CASHSHOP_GIFT_INFO_RESULT(0x147), // 现金礼物信息结果
    CASHSHOP_CHECK_NAME_CHANGE(0x148),// 检查改名
    CASHSHOP_CHECK_NAME_CHANGE_POSSIBLE_RESULT(0x149), // 检查改名可能性结果
    CASHSHOP_REGISTER_NEW_CHARACTER_RESULT(0x14A), // 注册新角色结果
    CASHSHOP_CHECK_TRANSFER_WORLD_POSSIBLE_RESULT(0x14B), // 检查转服可能性
    CASHSHOP_GACHAPON_STAMP_RESULT(0x14C), // 扭蛋印章结果
    CASHSHOP_CASH_ITEM_GACHAPON_RESULT(0x14D), // 现金物品扭蛋结果
    CASHSHOP_CASH_ITEM_GACHAPON_OPEN_RESULT(0x14E), // 现金扭蛋打开结果
    KEYMAP(0x14F),                    // 键盘映射
    AUTO_HP_POT(0x150),               // 自动 HP 药水
    AUTO_MP_POT(0x151),               // 自动 MP 药水
    SEND_TV(0x155),                   // 发送电视
    REMOVE_TV(0x156),                 // 移除电视
    ENABLE_TV(0x157),                 // 启用电视
    MTS_OPERATION2(0x15B),            // MTS 操作 2
    MTS_OPERATION(0x15C),             // MTS 操作
    MAPLELIFE_RESULT(0x15D),          // MapleLife 结果
    MAPLELIFE_ERROR(0x15E),           // MapleLife 错误
    VICIOUS_HAMMER(0x162),            // 恶毒锤子
    VEGA_SCROLL(0x166),               // VEGA 卷轴
    UPDATE_HPMPAALERT(0x1000);        // 更新 HP/MP 警报

    private final int code;

    private SendOpcode(int code) {
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
