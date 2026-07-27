package com.jjktbf.model.move;

/**
 * Describes the defensive behaviour of a DEFENSIVE-tagged move.
 *
 * <p>Stat-boosting defensive utility moves (e.g. raising the Defense stat)
 * are handled via the UTILITY tag and ability effects — not here.</p>
 *
 * <p>The active defense types form a small hierarchy. {@link #BLOCK} is further
 * specialised by a {@link BlockStyle} (percentage vs flat reduction); {@link #PARRY}
 * and {@link #DODGE} carry their own parameters on {@link Move}.</p>
 */
public enum DefenseType {

    /** No defensive behaviour — this is not a defensive move. */
    NONE,

    /**
     * Damage block. Reduces the attack value of incoming attacks that fire while
     * the block window is active. The reduction formula is chosen by
     * {@link BlockStyle} (set on the move):
     * <ul>
     *   <li>{@link BlockStyle#PERCENTAGE} — {@code blockDamageReduction}% (0–100;
     *       100 = full negation, outcome BLOCKED).</li>
     *   <li>{@link BlockStyle#FLAT} — subtracts {@code blockFlatReduction} (floor 1).</li>
     * </ul>
     * Applied before the defender's Defense stat.
     *
     * <p>Duration is controlled by {@code blockDuration}:
     * {@code 0} = use the move's apCost as the window, {@code -1} = until end of
     * round, {@code >0} = that many AP ticks from the unleash point.</p>
     *
     * <p>Tag filtering via {@code blockAffectedTags}: a block fires iff it covers
     * every damage tag the incoming attack uses (attack tags ⊆ block tags).
     * null/empty = covers all damage types. See
     * {@link Move#coveredByBlockTags}.</p>
     *
     * <p>A GUARD_BREAK attack ignores blocks entirely. Blocks are subject to the
     * potency gate: a block only applies when {@code block.potency >= attack.potency}.</p>
     */
    BLOCK,

    /**
     * Parry. A short reactive window that, when an attack lands inside it, negates
     * the attack's damage entirely (no reduction roll — full negation). Requires
     * a weapon ({@code weaponRequired} is forced on for parry moves).
     *
     * <p>On a successful parry of a non-GUARD_BREAK attack, the attacker is
     * {@link StatusEffectType#STAGGER staggered} for {@code parryStaggerTicks}
     * AP ticks (0 = no stagger). GUARD_BREAK attacks are still damage-negated by
     * a parry but do not get staggered.</p>
     *
     * <p>Parries are potency-gated: a parry only applies when
     * {@code parry.potency >= attack.potency}. GUARD_BREAK does NOT bypass a
     * parry (only blocks).</p>
     */
    PARRY,

    /**
     * Dodge. While the dodge window is active, each incoming attack that matches
     * the {@code dodgeScope} (MELEE / RANGED / BOTH) has a {@code dodgeChance}%
     * probability to be avoided entirely — no damage, outcome DODGED. Dodge is
     * type-agnostic (works vs physical and cursed energy) and is <b>not</b>
     * potency-gated. (Future AOE attacks will bypass dodge.)
     *
     * <p>Uses the same {@code blockDuration} window rules as BLOCK.</p>
     */
    DODGE,

    /**
     * Shield. Reserved for a future defence flavour. Not yet implemented — the
     * editor exposes it as a placeholder and the engine treats it as NONE.
     */
    SHIELD
}
