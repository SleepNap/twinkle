package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/** 账号-管理员角色关联（RBAC 多对多，{@code account_id} 关联 {@code account_records.id}）。 */
@Table("account_admin_role")
@Getter
@Setter
public class AccountAdminRole {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    private Long roleId;
}
