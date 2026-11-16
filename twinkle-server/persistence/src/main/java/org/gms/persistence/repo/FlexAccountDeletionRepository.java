package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.entity.AccountAdminRole;
import org.gms.persistence.entity.AdminSession;
import org.gms.persistence.entity.ApiKeyRecord;
import org.gms.persistence.entity.BuddyListEntity;
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
public final class FlexAccountDeletionRepository implements AccountDeletionRepository {

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

    /**
     * 兼容装配：不含账号仓库两表（既有单元测试按此签名手工 new，未注册 storage mapper）。
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
                accountAdminRoleMapper, adminSessionMapper, apiKeyMapper, null, null);
    }

    /** 完整装配（MyBatisFlexFactory 使用）：账号删除同时清理账号仓库物品行与仓库头。 */
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
            StorageRecordMapper storageRecordMapper) {
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
    }

    @Override
    public DeletionResult deleteByAccountId(long accountId) {
        return TransactionalManager.exec(() -> deleteInTransaction(accountId), Propagation.REQUIRED, null);
    }

    private DeletionResult deleteInTransaction(long accountId) {
        List<Long> characterIds = characterMapper.selectListByQuery(QueryWrapper.create()
                        .select(PlayerCharacterRecord::getId)
                        .where(PlayerCharacterRecord::getAccountId).eq(accountId))
                .stream()
                .map(PlayerCharacterRecord::getId)
                .toList();

        int relatedRows = 0;
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
}
