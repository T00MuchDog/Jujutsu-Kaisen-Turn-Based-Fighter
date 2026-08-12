package com.jjktbf.model.move;

/**
 * Human-readable battle-log lines for action-blocking status effects.
 *
 * <p>Stat changes and ordinary expiry are already represented by the HUD, so
 * only states that visibly prevent actions receive narration.
 */
public final class StatusEffectMessages {

    private StatusEffectMessages() {}

    /** Ordinary expiry is implied by the HUD. */
    public static String expiryMessage(String characterName, StatusEffectType type) {
        return "";
    }

    /** Describe a newly applied action-blocking status. */
    public static String applicationMessage(
        String sourceName,
        String targetName,
        StatusEffectType type,
        boolean sourceIsTarget
    ) {
        String target = targetName == null || targetName.isBlank() ? "Someone" : targetName;
        if (type == StatusEffectType.SLEEP) return target + " fell asleep!";
        if (type == StatusEffectType.STAGGER) return target + " was staggered!";
        return "";
    }
}
