package com.jjktbf.model.move;

/**
 * Describes the defensive behavior of a DEFENSIVE-tagged move.
 *
 * Stat-boosting defensive utility moves (e.g. raising the Defense stat)
 * are handled via the UTILITY tag and ability effects — not here.
 */
public enum DefenseType {

    /** No defensive behavior — this is not a defensive move. */
    NONE,

    /**
     * Percentage-based damage block.
     * Incoming attacks that fire while this block is active have their damage
     * reduced by blockDamageReduction % (0–100). 100 = full negation.
     * Applied before the defender's Defense stat.
     *
     * Duration is controlled by blockDuration:
     *   0  = use the move's apCost as the window
     *  -1  = lasts until end of round
     *  >0  = that many AP ticks from the block's unleash point
     *
     * Tag filtering via blockAffectedTags: a block fires iff it covers every
     * damage tag the incoming attack uses (attack tags ⊆ block tags). null/empty
     * = covers all damage types. See {@link com.jjktbf.model.move.Move#coveredByBlockTags}.
     */
    PERCENTAGE_BLOCK,

    /**
     * Flat damage block.
     * Incoming attacks that fire while this block is active have a fixed
     * amount subtracted from their final damage (blockFlatReduction).
     * Damage cannot go below 1 from this reduction alone.
     * Applied before the defender's Defense stat.
     *
     * Uses the same duration and tag-filtering rules as PERCENTAGE_BLOCK.
     */
    FLAT_BLOCK
}
