package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型倍率（V12 迁移建表）。按模型标识匹配，积分 = token × 倍率（倍率放大 1e4 存整数）。
 */
@Table("model_rate")
@Getter
@Setter
public class ModelRate {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String modelKey;
    private Integer inputRate;
    private Integer outputRate;
    private Integer enabled;
}
