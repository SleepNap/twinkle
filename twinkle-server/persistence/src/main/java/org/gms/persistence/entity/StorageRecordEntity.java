package org.gms.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号仓库头实体（storage/trunk 持久化，V19 迁移建表 {@code storage_records}）。
 *
 * <p>一个账号在一个世界只有一份仓库（唯一索引 {@code idx_storage_records_account_world}）；
 * {@code row_version} 是乐观锁版本门：写入金币/槽位数必须带期望版本，成功后版本 +1，
 * 迟到写入因版本不匹配而落空。{@code updated_at} 以字符串存库，与服务端 {@code Instant} 文本一致。
 * 字段 camelCase，MyBatis-Flex 自动转 snake_case 列名。
 */
@Table("storage_records")
@Getter
@Setter
public class StorageRecordEntity {

    @Id(keyType = KeyType.Auto)
    private Long storageRecordId;
    private int accountId;
    private int world;
    /** 仓库可容纳的槽位数（每个栏目独立计数），默认 4 与迁移默认值一致。 */
    private int slotCount = 4;
    private int meso;
    private int rowVersion;
    private String updatedAt;
}
