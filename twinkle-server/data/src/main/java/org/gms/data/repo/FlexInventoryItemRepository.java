package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.mapper.InventoryItemMapper;

import java.util.List;

/**
 * MyBatis-Flex 实现的背包物品仓库（M3-5 存档）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 */
public class FlexInventoryItemRepository implements InventoryItemRepository {

    private final InventoryItemMapper mapper;

    public FlexInventoryItemRepository(InventoryItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<InventoryItemEntity> findByCharacterId(long characterId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(InventoryItemEntity::getCharacterId).eq(characterId));
    }

    @Override
    public void replaceAll(long characterId, List<InventoryItemEntity> items) {
        mapper.deleteByQuery(QueryWrapper.create()
                .where(InventoryItemEntity::getCharacterId).eq(characterId));
        for (InventoryItemEntity item : items) {
            mapper.insert(item);
        }
    }
}
