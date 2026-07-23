package com.jjktbf.model.move;

/**
 * Human-readable flavour lines for a status effect expiring on a combatant.
 *
 * <p>The combat engine logs both the activation and the expiry of moves and
 * effects. Stat effects are distinct from defensive blocks, so their expiry
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
}
