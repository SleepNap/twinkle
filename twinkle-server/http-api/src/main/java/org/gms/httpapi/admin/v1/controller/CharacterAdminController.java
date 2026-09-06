package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.entity.QuestProgressEntity;
import org.gms.persistence.entity.QuestStatusEntity;
import org.gms.persistence.entity.SkillEntity;
import org.gms.persistence.repo.GameAccountRepository;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.persistence.repo.InventoryItemRepository;
import org.gms.persistence.repo.QuestRepository;
import org.gms.persistence.repo.SkillRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理控制台角色持久化详情聚合，只读且不触碰频道内存对象。 */
@Controller(ApiRoutes.ADMIN_V1 + "/accounts/{accountId}/characters")
@Produces(MediaType.APPLICATION_JSON)
public final class CharacterAdminController {

    private final GameAccountRepository accountRepository;
    private final PlayerCharacterRepository characterRepository;
    private final InventoryItemRepository inventoryRepository;
    private final QuestRepository questRepository;
    private final SkillRepository skillRepository;
    private final BuddyListRepository buddyRepository;

    public CharacterAdminController(GameAccountRepository accountRepository,
                                    PlayerCharacterRepository characterRepository,
                                    InventoryItemRepository inventoryRepository,
                                    QuestRepository questRepository,
                                    SkillRepository skillRepository,
                                    BuddyListRepository buddyRepository) {
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
        this.inventoryRepository = inventoryRepository;
        this.questRepository = questRepository;
        this.skillRepository = skillRepository;
        this.buddyRepository = buddyRepository;
    }

    @Get("/{characterId}")
    public HttpResponse<?> detail(@PathVariable long accountId, @PathVariable long characterId) {
        GameAccountRecord account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        PlayerCharacterRecord character = characterRepository.findById(characterId).orElse(null);
        if (character == null || character.getAccountId() == null || character.getAccountId() != accountId) {
            return HttpResponse.notFound(Map.of("error", "character_not_found"));
        }

        List<QuestProgressEntity> progress = questRepository.findProgressByCharacterId(characterId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("character", characterMap(character));
        result.put("currencies", currencyMap(account, character));
        result.put("inventory", inventoryRepository.findByCharacterId(characterId).stream()
                .map(CharacterAdminController::inventoryMap).toList());
        result.put("quests", questRepository.findStatusesByCharacterId(characterId).stream()
                .map(status -> questMap(status, progress)).toList());
        result.put("skills", skillRepository.findByCharacterId(characterId).stream()
                .map(CharacterAdminController::skillMap).toList());
        result.put("buddies", buddyRepository.findByOwner(characterId).stream()
                .map(this::buddyMap).toList());
        return HttpResponse.ok(result);
    }

    private static Map<String, Object> characterMap(PlayerCharacterRecord character) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", character.getId());
        result.put("accountId", character.getAccountId());
        result.put("name", safe(character.getName()));
        result.put("world", character.getWorld());
        result.put("level", character.getLevel());
        result.put("exp", character.getExp());
        result.put("job", character.getJob());
        result.put("map", character.getMap());
        result.put("spawnPoint", character.getSpawnPoint());
        result.put("hp", character.getHp());
        result.put("maxHp", character.getMaxHp());
        result.put("mp", character.getMp());
        result.put("maxMp", character.getMaxMp());
        result.put("strStat", character.getStrStat());
        result.put("dexStat", character.getDexStat());
        result.put("intStat", character.getIntStat());
        result.put("lukStat", character.getLukStat());
        result.put("ap", character.getAp());
        result.put("sp", safe(character.getSp()));
        result.put("fame", character.getFame());
        result.put("gm", character.getGm());
        result.put("partyId", character.getParty());
        result.put("guildId", character.getGuildId());
        result.put("guildRank", character.getGuildRank());
        result.put("buddyCapacity", character.getBuddyCapacity());
        result.put("createdAt", safe(character.getCreateDate()));
        result.put("lastLogoutTime", safe(character.getLastLogoutTime()));
        result.put("lastExpGainTime", safe(character.getLastExpGainTime()));
        return result;
    }

    private static Map<String, Object> currencyMap(GameAccountRecord account, PlayerCharacterRecord character) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meso", character.getMeso());
        result.put("nxCredit", number(account.getNxCredit()));
        result.put("maplePoint", number(account.getMaplePoint()));
        result.put("nxPrepaid", number(account.getNxPrepaid()));
        result.put("rewardPoints", account.getRewardPoints());
        result.put("votePoints", account.getVotePoints());
        return result;
    }

    private static Map<String, Object> inventoryMap(InventoryItemEntity item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getInventoryItemId());
        result.put("itemId", item.getItemId());
        result.put("type", item.getType());
        result.put("inventoryType", item.getInventoryType());
        result.put("position", item.getPosition());
        result.put("quantity", item.getQuantity());
        result.put("owner", safe(item.getOwner()));
        result.put("flag", item.getFlag());
        result.put("expiration", item.getExpiration());
        result.put("cashId", item.getCashId());
        result.put("petId", item.getPetId());
        result.put("upgradeSlots", item.getUpgradeSlots());
        result.put("itemLevel", item.getItemLevel());
        result.put("itemExp", item.getItemExp());
        result.put("strStat", item.getStrStat());
        result.put("dexStat", item.getDexStat());
        result.put("intStat", item.getIntStat());
        result.put("lukStat", item.getLukStat());
        result.put("wAtk", item.getWAtk());
        result.put("mAtk", item.getMAtk());
        result.put("wDef", item.getWDef());
        result.put("mDef", item.getMDef());
        result.put("petName", safe(item.getPetName()));
        result.put("petLevel", item.getPetLevel());
        return result;
    }

    private static Map<String, Object> questMap(QuestStatusEntity status,
                                                 List<QuestProgressEntity> allProgress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questId", status.getQuest());
        result.put("status", status.getStatus());
        result.put("time", status.getTime());
        result.put("expires", status.getExpires());
        result.put("forfeited", status.getForfeited());
        result.put("completed", status.getCompleted());
        result.put("info", status.getInfo());
        result.put("progress", allProgress.stream()
                .filter(item -> status.getQuestStatusId() != null
                        && item.getQuestStatusId() == status.getQuestStatusId().intValue())
                .map(CharacterAdminController::progressMap).toList());
        return result;
    }

    private static Map<String, Object> progressMap(QuestProgressEntity progress) {
        return Map.of("progressId", progress.getProgressId(), "value", safe(progress.getProgress()));
    }

    private static Map<String, Object> skillMap(SkillEntity skill) {
        return Map.of(
                "skillId", skill.getSkillId(),
                "level", skill.getSkillLevel(),
                "masterLevel", skill.getMasterLevel(),
                "expiration", skill.getExpiration());
    }

    private Map<String, Object> buddyMap(BuddyListEntity buddy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characterId", buddy.getBuddyId());
        result.put("name", characterRepository.findById(buddy.getBuddyId())
                .map(PlayerCharacterRecord::getName).orElse(""));
        result.put("status", safe(buddy.getStatus()));
        result.put("createdAt", safe(buddy.getCreatedAt()));
        return result;
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
