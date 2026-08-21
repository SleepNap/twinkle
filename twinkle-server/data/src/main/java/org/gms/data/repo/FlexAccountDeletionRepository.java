package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import org.gms.data.entity.Account;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.entity.AdminSession;
import org.gms.data.entity.ApiKeyRecord;
import org.gms.data.entity.BuddyListEntity;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.PointAccount;
import org.gms.data.entity.PointTransaction;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.entity.SkillEntity;
import org.gms.data.mapper.AccountAdminRoleMapper;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.AdminSessionMapper;
import org.gms.data.mapper.ApiKeyMapper;
import org.gms.data.mapper.BuddyListMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.PointAccountMapper;
import org.gms.data.mapper.PointTransactionMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.mapper.SkillMapper;

import java.util.List;

/** MyBatis-Flex 账号级联删除实现，所有删除在同一事务中完成。 */
public final class FlexAccountDeletionRepository implements AccountDeletionRepository {

    private final AccountMapper accountMapper;
    private final CharacterMapper characterMapper;
    private final InventoryItemMapper inventoryItemMapper;
    private final QuestStatusMapper questStatusMapper;
    private final QuestProgressMapper questProgressMapper;
    private final SkillMapper skillMapper;
    private final BuddyListMapper buddyListMapper;
    private final PointAccountMapper pointAccountMapper;
    private final PointTransactionMapper pointTransactionMapper;
    private final AccountAdminRoleMapper accountAdminRoleMapper;
    private final AdminSessionMapper adminSessionMapper;
    private final ApiKeyMapper apiKeyMapper;

    public FlexAccountDeletionRepository(
            AccountMapper accountMapper,
            CharacterMapper characterMapper,
            InventoryItemMapper inventoryItemMapper,
            QuestStatusMapper questStatusMapper,
            QuestProgressMapper questProgressMapper,
            SkillMapper skillMapper,
            BuddyListMapper buddyListMapper,
            PointAccountMapper pointAccountMapper,
            PointTransactionMapper pointTransactionMapper,
            AccountAdminRoleMapper accountAdminRoleMapper,
            AdminSessionMapper adminSessionMapper,
            ApiKeyMapper apiKeyMapper) {
        this.accountMapper = accountMapper;
        this.characterMapper = characterMapper;
        this.inventoryItemMapper = inventoryItemMapper;
        this.questStatusMapper = questStatusMapper;
        this.questProgressMapper = questProgressMapper;
        this.skillMapper = skillMapper;
        this.buddyListMapper = buddyListMapper;
        this.pointAccountMapper = pointAccountMapper;
        this.pointTransactionMapper = pointTransactionMapper;
        this.accountAdminRoleMapper = accountAdminRoleMapper;
        this.adminSessionMapper = adminSessionMapper;
        this.apiKeyMapper = apiKeyMapper;
    }

    @Override
    public DeletionResult deleteByAccountId(long accountId) {
        return TransactionalManager.exec(() -> deleteInTransaction(accountId), Propagation.REQUIRED, null);
    }

    private DeletionResult deleteInTransaction(long accountId) {
        List<Long> characterIds = characterMapper.selectListByQuery(QueryWrapper.create()
                        .select(Character::getId)
                        .where(Character::getAccountId).eq(accountId))
                .stream()
                .map(Character::getId)
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
        relatedRows += pointTransactionMapper.deleteByQuery(QueryWrapper.create()
                .where(PointTransaction::getAccountId).eq(accountId));
        relatedRows += pointAccountMapper.deleteByQuery(QueryWrapper.create()
                .where(PointAccount::getAccountId).eq(accountId));
        relatedRows += accountAdminRoleMapper.deleteByQuery(QueryWrapper.create()
                .where(AccountAdminRole::getAccountId).eq(accountId));
        relatedRows += adminSessionMapper.deleteByQuery(QueryWrapper.create()
                .where(AdminSession::getAccountId).eq(accountId));
        relatedRows += apiKeyMapper.deleteByQuery(QueryWrapper.create()
                .where(ApiKeyRecord::getOwnerAccountId).eq(accountId));

        int characters = characterMapper.deleteByQuery(QueryWrapper.create()
                .where(Character::getAccountId).eq(accountId));
        accountMapper.deleteByQuery(QueryWrapper.create().where(Account::getId).eq(accountId));
        return new DeletionResult(characters, relatedRows);
    }
}
