package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;

import java.util.*;

/**
 * Abstract base for all combat-capable characters.
 *
 * Holds:
 *  - Identity and classification
 *  - Immutable base stats (CharacterStats)
 *  - Derived combat stats (CombatStats), computed on construction
 *  - The character's move pool
 *  - Innate technique name (null if none, e.g. "Shrine", "Blood Manipulation")
 *
 * Does NOT hold mutable battle state — that lives in BattleCombatant.
 *
 * Technique gating:
 *   Moves with a requiredTechniqueName are only accessible to characters whose
 *   innateTechniqueName matches (case-insensitive). Characters with no innate
 *   technique (innateTechniqueName == null) cannot use any technique-restricted move.
 */
public abstract class Character extends Entity {

    private final CharacterStats baseStats;
    private final CombatStats    combatStats;
    private final CharacterType  type;

    /**
     * The human-readable name of this character's innate cursed technique.
     * e.g. "Shrine", "Blood Manipulation", "Infinite Void".
     * Null if the character has no innate technique.
     * Matched case-insensitively against Move.requiredTechniqueId.
     */
    private final String innateTechniqueName;

    /**
     * The full pool of moves this character knows.
     * Guaranteed moves (Basic Punch, Basic Block) are always present.
     * All other moves are validated against stat prerequisites, technique
     * possession, and slot budget at construction time.
     */
    private final List<Move> knownMoves;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    protected Character(
        String         id,
        String         name,
        CharacterType  type,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves
    ) {
        super(id, name);
        Objects.requireNonNull(type,      "CharacterType cannot be null");
        Objects.requireNonNull(baseStats, "CharacterStats cannot be null");

        this.type               = type;
        this.baseStats          = baseStats;
        this.combatStats        = new CombatStats(baseStats);
        this.innateTechniqueName = innateTechniqueName;
        this.knownMoves         = Collections.unmodifiableList(
            validateAndBuildMoveList(knownMoves, baseStats, combatStats, innateTechniqueName)
        );
    }

    // -------------------------------------------------------------------------
    // Move validation
    // -------------------------------------------------------------------------

    private static List<Move> validateAndBuildMoveList(
        List<Move>     moves,
        CharacterStats cs,
        CombatStats    combatStats,
        String         innateTechniqueName
    ) {
        if (moves == null) return List.of();

        Map<MoveCategory, Integer> slotUsed = new EnumMap<>(MoveCategory.class);
        List<Move> validated = new ArrayList<>();

        for (Move move : moves) {
            if (move.isGuaranteedMove()) {
                validated.add(move);
                continue;
            }

            // --- 1. Prerequisite stats ---
            for (Map.Entry<String, Integer> prereq : move.getPrerequisites().entrySet()) {
                int actual = getStatByName(cs, prereq.getKey());
                if (actual < prereq.getValue()) {
                    throw new IllegalArgumentException(
                        "Character does not meet prerequisite for move '" + move.getName()
                        + "': needs " + prereq.getKey() + " >= " + prereq.getValue()
                        + " but has " + actual
                    );
                }
            }

            // --- 2. Technique restriction ---
            // Move.getRequiredTechniqueId() stores the technique name (renamed field, same slot)
            if (move.getRequiredTechniqueId() != null) {
                if (innateTechniqueName == null
                    || !move.getRequiredTechniqueId().equalsIgnoreCase(innateTechniqueName)) {
                    throw new IllegalArgumentException(
                        "Character does not possess required technique '"
                        + move.getRequiredTechniqueId()
                        + "' for move '" + move.getName() + "'"
                    );
                }
            }

            // --- 3. Slot budget (DEFENSIVE and UTILITY are free — not slot-gated) ---
            MoveCategory cat = move.getCategory();
            if (SlotBudgetEnforcer.isSlotGated(cat)) {
                int used      = slotUsed.getOrDefault(cat, 0);
                int available = SlotBudgetEnforcer.slotBudgetFor(combatStats, cs, cat);
                if (used >= available) {
                    throw new IllegalArgumentException(
                        "Character has no available slots for category " + cat
                        + " (budget=" + available + ") when trying to add move '" + move.getName() + "'"
                    );
                }
                slotUsed.put(cat, used + 1);
            }

            validated.add(move);
        }

        return validated;
    }

    /** Package-accessible and public delegate — routes through CharacterStats.getByName(). */
    public static int getStatByName(CharacterStats cs, String statName) {
        return cs.getByName(statName);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public CharacterStats  getBaseStats()           { return baseStats; }
    public CombatStats     getCombatStats()          { return combatStats; }
    public CharacterType   getType()                 { return type; }
    public String          getInnateTechniqueName()  { return innateTechniqueName; }
    public List<Move>      getKnownMoves()           { return knownMoves; }
    public boolean         hasInnateTechnique()      { return innateTechniqueName != null; }

    /** @deprecated Use getInnateTechniqueName(). Kept for any callers using the old name. */
    @Deprecated
    public String          getInnateTechniqueId()    { return innateTechniqueName; }
}
