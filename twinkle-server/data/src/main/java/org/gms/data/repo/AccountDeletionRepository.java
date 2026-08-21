package org.gms.data.repo;

/** 管理侧账号级联删除仓库；审计记录不随业务账号删除。 */
public interface AccountDeletionRepository {

    /** 删除账号、账号下所有角色以及当前库中直接关联的业务数据。 */
    DeletionResult deleteByAccountId(long accountId);

    /** 级联删除结果，用于管理接口审计与反馈。 */
    public record DeletionResult(int characters, int relatedRows) {
    }
}
