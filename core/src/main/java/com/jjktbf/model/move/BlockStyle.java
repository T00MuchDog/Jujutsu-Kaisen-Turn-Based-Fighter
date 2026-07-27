package com.jjktbf.model.move;

/**
 * The reduction formula used by a {@link DefenseType#BLOCK} move.
 *
 * <p>Only meaningful when a move's {@link Move#getDefenseType() defence type}
 * is {@link DefenseType#BLOCK}; ignored for every other type.</p>
 */
public enum BlockStyle {

    /** Reduce the attack value by {@code blockDamageReduction} percent (0–100; 100 = full negation). */
    PERCENTAGE,

    /** Subtract a flat {@code blockFlatReduction} from the attack value (floor 1). */
    FLAT
}
