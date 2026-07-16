package com.jjktbf.model.move;

/**
 * Human-readable flavour lines for a status effect expiring on a combatant.
 *
 * <p>The combat engine logs both the activation and the expiry of moves and
 * effects. Stat-boost self-effects (POWER_UP, FOCUS, …) are a distinct category
 * from defensive blocks, so their expiry lines read differently — a guard
 * "drops" while a surge "fades". This helper keeps the per-effect wording in one
 * place and off the {@link Move} class, which intentionally does not depend on
 * {@link StatusEffectType} for its own message generation.
 *
 * <p>Every effect type resolves to a non-null line. Enhancing self-effects each
 * carry bespoke wording; the remaining (debuff) types fall back to a neutral
 * "&lt;name&gt;'s &lt;TYPE&gt; expires." so adding a new status effect never
 * produces a blank log line.
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
        String line = switch (type) {
            case POWER_UP      -> name + "'s power surge fades.";
            case DEFENSE_UP    -> name + "'s defensive focus settles.";
            case FOCUS         -> name + "'s focus wavers.";
            case SPEED_UP      -> name + "'s burst of speed wears off.";
            case CE_OUTPUT_UP  -> name + "'s cursed energy surge subsides.";
            case BARRIER       -> name + "'s barrier shatters.";
            default            -> name + "'s " + type.name() + " expires.";
        };
        return line;
    }
}
