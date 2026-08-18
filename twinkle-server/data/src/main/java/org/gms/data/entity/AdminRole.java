package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Web 控制台管理员角色（RBAC）。{@code permissions} 为逗号分隔的权限点集合，{@code *} 表示全部权限。
 */
@Table("admin_role")
@Getter
@Setter
public class AdminRole {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String roleCode;
    private String displayName;
    private String description;
    private String permissions;
    private String createdAt;
    private String updatedAt;
}
