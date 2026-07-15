package com.jjktbf.model.move;

/**
 * The two move-pool categories that determine which stat grants a move's slot.
 *
 * <p>A move's pool is orthogonal to its {@link MoveCategory} (which governs the
 * power formula and Black Flash eligibility) and to its tactical function
 * (offensive / defensive / utility — see the {@code ATTACK}, {@code DEFENSIVE},
 * {@code UTILITY} tags). Pool only answers one question: <b>which stat's budget
 * does this move draw from?</b>
 *
 * <ul>
 *   <li>{@link #COMBAT_ARTS} — moves carrying the {@code PHYSICAL} tag.
 *       Slot count granted by the Combat Ability stat.</li>
 *   <li>{@link #JUJUTSU_ARTS} — moves without the {@code PHYSICAL} tag.
 *       Slot count granted by the Jujutsu Skill stat.</li>
 * </ul>
 *
 * <p>Any hybrid move containing the {@code PHYSICAL} tag counts as a Combat Art.
 * A move with no {@code PHYSICAL} tag is always a Jujutsu Art, regardless of
 * whether it is offensive, defensive, or utility.
 */
public enum MovePool {

    /** Moves containing the PHYSICAL tag. Slots granted by Combat Ability. */
    COMBAT_ARTS,

    /** Moves without the PHYSICAL tag. Slots granted by Jujutsu Skill. */
    JUJUTSU_ARTS;

    /**
     * Resolve a pool from a move's raw tag set. This is the authoritative
     * derivation used at load time (e.g. by {@code MoveData.derivedPool()}),
     * because {@link MoveCategory} collapses away the PHYSICAL tag for
     * defensive/utility moves (e.g. {@code [PHYSICAL, DEFENSIVE]} derives to
     * {@link MoveCategory#DEFENSIVE}).
     *
     * @param tags the move's raw tag names (case-insensitive)
     * @return {@link #COMBAT_ARTS} if any tag equals {@code "PHYSICAL"}, else {@link #JUJUTSU_ARTS}
     */
    public static MovePool fromTags(Iterable<String> tags) {
        if (tags != null) {
            for (String t : tags) {
                if (t != null && "PHYSICAL".equalsIgnoreCase(t.trim())) {
                    return COMBAT_ARTS;
                }
            }
        }
        return JUJUTSU_ARTS;
    }

    /**
     * Fallback used when only a {@link MoveCategory} is available (e.g. moves
     * built directly via the {@link Move.Builder} without an explicit pool).
     * Returns {@link #COMBAT_ARTS} iff the category's tag set contains
     * {@link MoveTag#PHYSICAL}.
     *
     * <p>This is correct for categories that preserve the PHYSICAL tag, but is a
     * <b>lossy approximation</b> for defensive/utility categories whose original
     * tag set may have included PHYSICAL. Authoritative callers should prefer
     * {@link #fromTags(Iterable)}.
     */
    public static MovePool fromCategory(MoveCategory category) {
        if (category != null && category.getTags().contains(MoveTag.PHYSICAL)) {
            return COMBAT_ARTS;
        }
        return JUJUTSU_ARTS;
    }
}
