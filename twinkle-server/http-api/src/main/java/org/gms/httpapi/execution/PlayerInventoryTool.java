package org.gms.httpapi.execution;

import io.micronaut.http.HttpStatus;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.i18n.I18n;
import org.gms.service.admin.AdminService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** player.inventory.read：读取在线角色的频道内存背包真值。 */
public final class PlayerInventoryTool {

    private static final Set<String> INPUT_FIELDS = Set.of("characterId");

    private final AdminService adminService;
    private final ServerIdentity serverIdentity;

    public PlayerInventoryTool(AdminService adminService, ServerIdentity serverIdentity) {
        this.adminService = adminService;
        this.serverIdentity = serverIdentity;
    }

    public Map<String, Object> read(Map<String, Object> input, String requestId, String executionId) {
        for (String field : input.keySet()) {
            if (!INPUT_FIELDS.contains(field)) {
                throw invalid(I18n.message("error.inventory.unknown_field", field), requestId, executionId);
            }
        }
        Object rawCharacterId = input.get("characterId");
        if (!(rawCharacterId instanceof String value) || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid(I18n.message("error.inventory.character_id_positive"), requestId, executionId);
        }
        final long characterId;
        try {
            characterId = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(I18n.message("error.inventory.character_id_out_of_range"), requestId, executionId);
        }
        AdminService.PlayerInventory snapshot = adminService.inventorySnapshot(characterId);
        if (snapshot == null) {
            throw new ToolProtocolException(HttpStatus.NOT_FOUND, "resource_not_found",
                    I18n.message("error.inventory.character_offline"), false, executionId, requestId,
                    Map.of("characterId", value));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminService.InventoryItemView item : snapshot.items()) {
            items.add(itemMap(item));
        }
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("serverId", serverIdentity.serverId());
        output.put("characterId", Long.toString(snapshot.characterId()));
        output.put("name", snapshot.name());
        output.put("stateVersion", Long.toString(snapshot.stateVersion()));
        output.put("itemCount", items.size());
        output.put("items", List.copyOf(items));
        output.put("observedAt", Instant.now().toString());
        return Map.copyOf(output);
    }

    private static Map<String, Object> itemMap(AdminService.InventoryItemView item) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("inventoryType", item.inventoryType());
        result.put("position", item.position());
        result.put("itemType", item.itemType());
        result.put("itemId", item.itemId());
        result.put("quantity", item.quantity());
        result.put("cashId", Long.toString(item.cashId()));
        result.put("petId", Integer.toString(item.petId()));
        result.put("owner", item.owner());
        result.put("flag", item.flag());
        result.put("expiration", item.expiration());
        if (item.equip() != null) {
            result.put("equip", equipMap(item.equip()));
        }
        if (item.pet() != null) {
            result.put("pet", petMap(item.pet()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> equipMap(AdminService.EquipView equip) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("upgradeSlots", equip.upgradeSlots());
        result.put("level", equip.level());
        result.put("strength", equip.strength());
        result.put("dexterity", equip.dexterity());
        result.put("intelligence", equip.intelligence());
        result.put("luck", equip.luck());
        result.put("hp", equip.hp());
        result.put("mp", equip.mp());
        result.put("weaponAttack", equip.weaponAttack());
        result.put("magicAttack", equip.magicAttack());
        result.put("weaponDefense", equip.weaponDefense());
        result.put("magicDefense", equip.magicDefense());
        result.put("accuracy", equip.accuracy());
        result.put("avoidability", equip.avoidability());
        result.put("hands", equip.hands());
        result.put("speed", equip.speed());
        result.put("jump", equip.jump());
        result.put("vicious", equip.vicious());
        result.put("itemLevel", equip.itemLevel());
        result.put("itemExp", equip.itemExp());
        result.put("ringId", equip.ringId());
        return Map.copyOf(result);
    }

    private static Map<String, Object> petMap(AdminService.PetView pet) {
        return Map.of(
                "name", pet.name(),
                "level", pet.level(),
                "closeness", pet.closeness(),
                "fullness", pet.fullness(),
                "attribute", pet.attribute(),
                "skill", pet.skill(),
                "remainLife", pet.remainLife(),
                "itemAttribute", pet.itemAttribute());
    }

    private static ToolProtocolException invalid(String message, String requestId, String executionId) {
        return new ToolProtocolException(HttpStatus.BAD_REQUEST, "invalid_input", message,
                false, executionId, requestId, Map.of());
    }
}
