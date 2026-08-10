package com.jjktbf.model.move;

/**
 * Human-readable lifecycle lines for status effects on combatants.
 *
 * <p>The combat engine logs both the activation and the expiry of moves and
 * effects. Status effects are distinct from defensive blocks, so their expiry
 * lines read differently. This helper keeps the wording in one
 * place and off the {@link Move} class, which intentionally does not depend on
 * {@link StatusEffectType} for its own message generation.
 *
 * <p>Every effect type resolves to a non-null line.
 */
public final class StatusEffectMessages {

    private StatusEffectMessages() {}

    /**
     * @param characterName the combatant the effect is expiring from
     * @param type          the status effect that just expired
     * @return a non-null, human-readable expiry line for the battle log
     */
    public static String expiryMessage(String characterName, StatusEffectType type) {
        String name = characterName == null ? "Someone" : characterName;
        return name + "'s " + type.displayName().toLowerCase() + " effect expires.";
    }

    /**
     * Describe a newly applied status with both its source and recipient. Self-applied
     * effects retain the more natural "gains" wording.
     */
    public static String applicationMessage(
        String sourceName,
        String targetName,
        StatusEffectType type,
        boolean sourceIsTarget
    ) {
        String source = sourceName == null || sourceName.isBlank() ? null : sourceName;
        String target = targetName == null || targetName.isBlank() ? "Someone" : targetName;
        if (source == null) return target + " receives " + type.displayName() + "!";
        if (sourceIsTarget) return target + " gains " + type.displayName() + "!";
        return source + " applies " + type.displayName() + " to " + target + "!";
    }
}
