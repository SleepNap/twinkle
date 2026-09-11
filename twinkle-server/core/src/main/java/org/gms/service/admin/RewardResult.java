package org.gms.service.admin;

/** 每人独立结果；待存档表示内存已应用，重试只能补存，不能再发一遍。 */
public record RewardResult(String batchId, long characterId, Status status) {
    public enum Status {
        APPLIED, ALREADY_APPLIED, OFFLINE, BUSY, NO_SPACE, INVALID,
        IDEMPOTENCY_CONFLICT, PERSISTENCE_PENDING, FAILED
    }

    public static RewardResult of(RewardGrant grant, Status status) {
        return new RewardResult(grant.batchId(), grant.characterId(), status);
    }
}
