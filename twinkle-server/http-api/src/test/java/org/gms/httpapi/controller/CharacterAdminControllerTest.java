package org.gms.httpapi.controller;

import io.micronaut.http.HttpResponse;
import org.gms.data.entity.Account;
import org.gms.data.entity.BuddyListEntity;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.entity.SkillEntity;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.BuddyListRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.QuestProgressSnapshot;
import org.gms.data.repo.QuestRepository;
import org.gms.data.repo.SkillRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterAdminControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesPersistedCharacterDetails() {
        Account account = account(7L);
        Character hero = character(71L, 7L, "hero");
        Character buddyCharacter = character(72L, 8L, "friend");

        InventoryItemEntity inventory = new InventoryItemEntity();
        inventory.setInventoryItemId(101L);
        inventory.setCharacterId(71);
        inventory.setItemId(2000005);
        inventory.setQuantity(12);

        QuestStatusEntity quest = new QuestStatusEntity();
        quest.setQuestStatusId(201L);
        quest.setCharacterId(71);
        quest.setQuest(1001);
        quest.setStatus(1);
        QuestProgressEntity progress = new QuestProgressEntity();
        progress.setQuestStatusId(201);
        progress.setProgressId(9001);
        progress.setProgress("3");

        SkillEntity skill = new SkillEntity();
        skill.setCharacterId(71);
        skill.setSkillId(1001004);
        skill.setSkillLevel(10);

        BuddyListEntity buddy = new BuddyListEntity();
        buddy.setOwnerId(71L);
        buddy.setBuddyId(72L);
        buddy.setStatus(BuddyListEntity.ACCEPTED);

        CharacterAdminController controller = controller(
                account, List.of(hero, buddyCharacter), List.of(inventory),
                List.of(quest), List.of(progress), List.of(skill), List.of(buddy));

        HttpResponse<?> response = controller.detail(7L, 71L);
        assertThat(response.code()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.body();
        assertThat((Map<String, Object>) body.get("character"))
                .containsEntry("name", "hero")
                .containsEntry("level", 30);
        assertThat((Map<String, Object>) body.get("currencies"))
                .containsEntry("meso", 123456)
                .containsEntry("nxCredit", 500);
        assertThat((List<Map<String, Object>>) body.get("inventory"))
                .extracting(item -> item.get("itemId")).containsExactly(2000005);
        assertThat((List<Map<String, Object>>) body.get("skills"))
                .extracting(item -> item.get("skillId")).containsExactly(1001004);
        assertThat((List<Map<String, Object>>) body.get("buddies"))
                .extracting(item -> item.get("name")).containsExactly("friend");

        Map<String, Object> questBody = ((List<Map<String, Object>>) body.get("quests")).getFirst();
        assertThat((List<Map<String, Object>>) questBody.get("progress"))
                .extracting(item -> item.get("value")).containsExactly("3");
    }

    @Test
    void rejectsCharacterOwnedByAnotherAccount() {
        CharacterAdminController controller = controller(
                account(7L), List.of(character(71L, 8L, "hero")), List.of(),
                List.of(), List.of(), List.of(), List.of());

        assertThat(controller.detail(7L, 71L).code()).isEqualTo(404);
    }

    private static CharacterAdminController controller(
            Account account,
            List<Character> characters,
            List<InventoryItemEntity> inventory,
            List<QuestStatusEntity> quests,
            List<QuestProgressEntity> progress,
            List<SkillEntity> skills,
            List<BuddyListEntity> buddies) {
        return new CharacterAdminController(
                new MemoryAccountRepository(account),
                new MemoryCharacterRepository(characters),
                new MemoryInventoryRepository(inventory),
                new MemoryQuestRepository(quests, progress),
                new MemorySkillRepository(skills),
                new MemoryBuddyRepository(buddies));
    }

    private static Account account(long id) {
        Account account = new Account();
        account.setId(id);
        account.setName("alice");
        account.setNxCredit(500);
        account.setMaplePoint(60);
        account.setNxPrepaid(70);
        return account;
    }

    private static Character character(long id, long accountId, String name) {
        Character character = new Character();
        character.setId(id);
        character.setAccountId(accountId);
        character.setName(name);
        character.setLevel(30);
        character.setMeso(123456);
        return character;
    }

    private record MemoryAccountRepository(Account account) implements AccountRepository {
        @Override public Optional<Account> findByName(String name) { return Optional.empty(); }
        @Override public Optional<Account> findById(Long id) {
            return account.getId().equals(id) ? Optional.of(account) : Optional.empty();
        }
        @Override public void insert(Account value) { }
        @Override public void update(Account value) { }
        @Override public List<Account> findByNameLike(String query, int limit) { return List.of(); }
    }

    private record MemoryCharacterRepository(List<Character> characters) implements CharacterRepository {
        @Override public List<Character> findByAccount(int accountId, int world) { return List.of(); }
        @Override public Optional<Character> findById(long id) {
            return characters.stream().filter(character -> character.getId() == id).findFirst();
        }
        @Override public boolean existsByName(String name) { return false; }
        @Override public void insert(Character character) { }
        @Override public void save(Character character) { }
    }

    private record MemoryInventoryRepository(List<InventoryItemEntity> items)
            implements InventoryItemRepository {
        @Override public List<InventoryItemEntity> findByCharacterId(long characterId) { return items; }
        @Override public void insert(InventoryItemEntity item) { }
        @Override public void replaceAll(long characterId, List<InventoryItemEntity> values) { }
    }

    private record MemoryQuestRepository(List<QuestStatusEntity> quests, List<QuestProgressEntity> progress)
            implements QuestRepository {
        @Override public List<QuestStatusEntity> findStatusesByCharacterId(long characterId) { return quests; }
        @Override public List<QuestProgressEntity> findProgressByCharacterId(long characterId) { return progress; }
        @Override public void replaceAll(long characterId, List<QuestStatusEntity> statuses,
                                         List<QuestProgressSnapshot> values) { }
    }

    private record MemorySkillRepository(List<SkillEntity> skills) implements SkillRepository {
        @Override public List<SkillEntity> findByCharacterId(long characterId) { return skills; }
        @Override public void replaceAll(long characterId, List<SkillEntity> values) { }
    }

    private record MemoryBuddyRepository(List<BuddyListEntity> buddies) implements BuddyListRepository {
        @Override public List<BuddyListEntity> findByOwner(long ownerId) { return buddies; }
        @Override public boolean exists(long ownerId, long buddyId) { return false; }
        @Override public boolean insertIfAbsent(BuddyListEntity buddy) { return false; }
        @Override public void updateStatus(long ownerId, long buddyId, String status) { }
        @Override public void delete(long ownerId, long buddyId) { }
    }
}
