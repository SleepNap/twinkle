package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import com.mybatisflex.core.update.UpdateChain;
import lombok.extern.log4j.Log4j2;
import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.entity.AccountAdminRole;
import org.gms.persistence.entity.AdminSession;
import org.gms.persistence.entity.ApiKeyRecord;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.entity.GuildRecordEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.entity.PointAccount;
import org.gms.persistence.entity.PointTransaction;
import org.gms.persistence.entity.QuestProgressEntity;
import org.gms.persistence.entity.QuestStatusEntity;
import org.gms.persistence.entity.SkillEntity;
import org.gms.persistence.entity.StorageItemEntity;
import org.gms.persistence.entity.StorageRecordEntity;
import org.gms.persistence.mapper.AccountAdminRoleMapper;
import org.gms.persistence.mapper.GameAccountRecordMapper;
import org.gms.persistence.mapper.AdminSessionMapper;
import org.gms.persistence.mapper.ApiKeyMapper;
import org.gms.persistence.mapper.BuddyListMapper;
import org.gms.persistence.mapper.GuildRecordMapper;
import org.gms.persistence.mapper.PlayerCharacterRecordMapper;
import org.gms.persistence.mapper.InventoryItemMapper;
import org.gms.persistence.mapper.PointAccountMapper;
import org.gms.persistence.mapper.PointTransactionMapper;
import org.gms.persistence.mapper.QuestProgressMapper;
import org.gms.persistence.mapper.QuestStatusMapper;
import org.gms.persistence.mapper.SkillMapper;
import org.gms.persistence.mapper.StorageItemMapper;
import org.gms.persistence.mapper.StorageRecordMapper;

import java.util.List;

/** MyBatis-Flex 账号级联删除实现，所有删除在同一事务中完成。 */
@Log4j2
public final class FlexAccountDeletionRepository implements AccountDeletionRepository {

    /** 无公会状态的职级（v83：职级 5 = 非公会成员）。 */
    private static final int NO_GUILD_RANK = 5;

    private final GameAccountRecordMapper accountMapper;
    private final PlayerCharacterRecordMapper characterMapper;
    private final InventoryItemMapper inventoryItemMapper;
    private final QuestStatusMapper questStatusMapper;
    private final QuestProgressMapper questProgressMapper;
    private final SkillMapper skillMapper;
    private final BuddyListMapper buddyListMapper;
    private final PointAccountMapper pointGameAccountRecordMapper;
    private final PointTransactionMapper pointTransactionMapper;
    private final AccountAdminRoleMapper accountAdminRoleMapper;
    private final AdminSessionMapper adminSessionMapper;
    private final ApiKeyMapper apiKeyMapper;
    /** 账号仓库两表（V19）：旧构造器装配时为 null，表示该装配不含 storage 表。 */
    private final StorageItemMapper storageItemMapper;
    private final StorageRecordMapper storageRecordMapper;
    /** 公会头（V20）：旧构造器装配时为 null，表示该装配不含 guild_record 表。 */
    private final GuildRecordMapper guildRecordMapper;

    /**
     * 兼容装配：不含账号仓库两表与公会头（既有单元测试按此签名手工 new，未注册 storage/guild mapper）。
     */
    public FlexAccountDeletionRepository(
            GameAccountRecordMapper accountMapper,
            PlayerCharacterRecordMapper characterMapper,
            InventoryItemMapper inventoryItemMapper,
            QuestStatusMapper questStatusMapper,
            QuestProgressMapper questProgressMapper,
            SkillMapper skillMapper,
            BuddyListMapper buddyListMapper,
            PointAccountMapper pointGameAccountRecordMapper,
            PointTransactionMapper pointTransactionMapper,
            AccountAdminRoleMapper accountAdminRoleMapper,
            AdminSessionMapper adminSessionMapper,
            ApiKeyMapper apiKeyMapper) {
        this(accountMapper, characterMapper, inventoryItemMapper, questStatusMapper, questProgressMapper,
                skillMapper, buddyListMapper, pointGameAccountRecordMapper, pointTransactionMapper,
                accountAdminRoleMapper, adminSessionMapper, apiKeyMapper, null, null, null);
    }

    /** 完整装配（MyBatisFlexFactory 使用）：账号删除同时清理账号仓库物品行、仓库头与公会关系。 */
    public FlexAccountDeletionRepository(
            GameAccountRecordMapper accountMapper,
            PlayerCharacterRecordMapper characterMapper,
            InventoryItemMapper inventoryItemMapper,
            QuestStatusMapper questStatusMapper,
            QuestProgressMapper questProgressMapper,
            SkillMapper skillMapper,
            BuddyListMapper buddyListMapper,
            PointAccountMapper pointGameAccountRecordMapper,
            PointTransactionMapper pointTransactionMapper,
            AccountAdminRoleMapper accountAdminRoleMapper,
            AdminSessionMapper adminSessionMapper,
            ApiKeyMapper apiKeyMapper,
            StorageItemMapper storageItemMapper,
            StorageRecordMapper storageRecordMapper,
            GuildRecordMapper guildRecordMapper) {
        this.accountMapper = accountMapper;
        this.characterMapper = characterMapper;
        this.inventoryItemMapper = inventoryItemMapper;
        this.questStatusMapper = questStatusMapper;
        this.questProgressMapper = questProgressMapper;
        this.skillMapper = skillMapper;
        this.buddyListMapper = buddyListMapper;
        this.pointGameAccountRecordMapper = pointGameAccountRecordMapper;
        this.pointTransactionMapper = pointTransactionMapper;
        this.accountAdminRoleMapper = accountAdminRoleMapper;
        this.adminSessionMapper = adminSessionMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.storageItemMapper = storageItemMapper;
        this.storageRecordMapper = storageRecordMapper;
        this.guildRecordMapper = guildRecordMapper;
    }

    @Override
    public DeletionResult deleteByAccountId(long accountId) {
        return TransactionalManager.exec(() -> deleteInTransaction(accountId), Propagation.REQUIRED, null);
    }

    private DeletionResult deleteInTransaction(long accountId) {
        // 角色快照一次取全（含 guild_id）：公会关系清理与后续逐表级联都复用这份快照，
        // 避免角色行删掉之后再想按 guild_id 找同公会其他成员。
        List<PlayerCharacterRecord> characterSnapshots = characterMapper.selectListByQuery(QueryWrapper.create()
                .where(PlayerCharacterRecord::getAccountId).eq(accountId));
        List<Long> characterIds = characterSnapshots.stream()
                .map(PlayerCharacterRecord::getId)
                .toList();

        // 公会关系必须先于角色行删除处理：会长随账号一起消失时要先把其余成员的 guild_id 复位。
        int relatedRows = releaseGuildMemberships(characterSnapshots, characterIds);
        if (!characterIds.isEmpty()) {
            relatedRows += questProgressMapper.deleteByQuery(QueryWrapper.create()
                    .where(QuestProgressEntity::getCharacterId).in(characterIds));
            relatedRows += questStatusMapper.deleteByQuery(QueryWrapper.create()
                    .where(QuestStatusEntity::getCharacterId).in(characterIds));
            relatedRows += skillMapper.deleteByQuery(QueryWrapper.create()
                    .where(SkillEntity::getCharacterId).in(characterIds));
            relatedRows += buddyListMapper.deleteByQuery(QueryWrapper.create()
                    .where(BuddyListEntity::getOwnerId).in(characterIds)
                    .or(BuddyListEntity::getBuddyId).in(characterIds));
        }
        QueryWrapper inventoryQuery = QueryWrapper.create()
                .where(InventoryItemEntity::getAccountId).eq(accountId);
        if (!characterIds.isEmpty()) {
            inventoryQuery.or(InventoryItemEntity::getCharacterId).in(characterIds);
        }
        relatedRows += inventoryItemMapper.deleteByQuery(inventoryQuery);
        // 账号仓库 storage/trunk：先删物品行再删仓库头（无外键约束，顺序仅为可读性）。
        if (storageItemMapper != null) {
            relatedRows += storageItemMapper.deleteByQuery(QueryWrapper.create()
                    .where(StorageItemEntity::getAccountId).eq(accountId));
        }
        if (storageRecordMapper != null) {
            relatedRows += storageRecordMapper.deleteByQuery(QueryWrapper.create()
                    .where(StorageRecordEntity::getAccountId).eq(accountId));
        }
        relatedRows += pointTransactionMapper.deleteByQuery(QueryWrapper.create()
                .where(PointTransaction::getAccountId).eq(accountId));
        relatedRows += pointGameAccountRecordMapper.deleteByQuery(QueryWrapper.create()
                .where(PointAccount::getAccountId).eq(accountId));
        relatedRows += accountAdminRoleMapper.deleteByQuery(QueryWrapper.create()
                .where(AccountAdminRole::getAccountId).eq(accountId));
        relatedRows += adminSessionMapper.deleteByQuery(QueryWrapper.create()
                .where(AdminSession::getAccountId).eq(accountId));
        relatedRows += apiKeyMapper.deleteByQuery(QueryWrapper.create()
                .where(ApiKeyRecord::getOwnerAccountId).eq(accountId));

        int characters = characterMapper.deleteByQuery(QueryWrapper.create()
                .where(PlayerCharacterRecord::getAccountId).eq(accountId));
        accountMapper.deleteByQuery(QueryWrapper.create().where(GameAccountRecord::getId).eq(accountId));
        return new DeletionResult(characters, relatedRows);
    }

    /**
     * 公会关系清理（必须在删除角色行之前调用）。
     *
     * <p>成员关系只有一处权威：{@code character_records.guild_id}/{@code guild_rank}（V20 注释）。
     * 两种分支：
     * <ul>
     *   <li>会长随账号一起被删（{@code guild_record.leader_id} 属于本账号）→ 公会没有会长就无法运作，
     *       直接解散：删除 {@code guild_record} 行，并把该公会其余成员的 {@code guild_id} 置 0、
     *       {@code guild_rank} 置 5。</li>
     *   <li>普通成员 → 只把本账号在该公会的角色 {@code guild_id} 置 0、{@code guild_rank} 置 5，
     *       公会与会长不动。</li>
     * </ul>
     *
     * <p>只 set 这两列必须走 {@link UpdateChain}：{@code updateByQuery(entity, qw)} 会把实体里非 null
     * 的原始类型字段（几十列 int/short）一起写回，抹掉其他存档数据。
     *
     * <p>异常不吞：mapper 抛出即让 {@code TransactionalManager.exec} 回滚整个账号删除。
     *
     * @return 被解散（删除）的公会头行数，计入 {@code relatedRows}；仅更新成员的复位不计行数
     */
    private int releaseGuildMemberships(List<PlayerCharacterRecord> characters, List<Long> characterIds) {
        if (guildRecordMapper == null || characterIds.isEmpty()) {
            // 兼容装配（不含 guild_record 表）或账号下无角色：无事可做。
            return 0;
        }
        // 同账号多角色可能同属一个公会：按 guild_id 去重，避免重复删除/重复更新。
        int[] guildIds = characters.stream()
                .mapToInt(PlayerCharacterRecord::getGuildId)
                .filter(guildId -> guildId != 0)
                .distinct()
                .toArray();
        int dissolvedGuilds = 0;
        for (int guildId : guildIds) {
            GuildRecordEntity guild = guildRecordMapper.selectOneById((long) guildId);
            if (guild != null && characterIds.contains(guild.getLeaderId())) {
                // 会长随账号消失 → 解散公会：先复位其余成员，再删公会头（无外键，顺序只为可读性）。
                UpdateChain.of(characterMapper)
                        .where(PlayerCharacterRecord::getGuildId).eq(guildId)
                        .and(PlayerCharacterRecord::getId).ne(guild.getLeaderId())
                        .set(PlayerCharacterRecord::getGuildId, 0)
                        .set(PlayerCharacterRecord::getGuildRank, NO_GUILD_RANK)
                        .update();
                dissolvedGuilds += guildRecordMapper.deleteById(guild.getGuildRecordId());
                log.info("账号解散公会：guildRecordId={}, name={}, leaderId={}, 成员已复位",
                        guild.getGuildRecordId(), guild.getName(), guild.getLeaderId());
            } else {
                // 普通成员（或公会头已不存在的残留 guild_id）：只摘出本账号在该公会的角色。
                UpdateChain.of(characterMapper)
                        .where(PlayerCharacterRecord::getGuildId).eq(guildId)
                        .and(PlayerCharacterRecord::getId).in(characterIds)
                        .set(PlayerCharacterRecord::getGuildId, 0)
                        .set(PlayerCharacterRecord::getGuildRank, NO_GUILD_RANK)
                        .update();
                log.info("账号退出公会：guildId={}, accountCharacters={}, 公会保留",
                        guildId, characterIds.size());
            }
        }
        return dissolvedGuilds;
    }
}
