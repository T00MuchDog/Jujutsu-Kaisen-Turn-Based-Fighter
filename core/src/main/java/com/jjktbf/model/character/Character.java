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
 *  - Innate technique identifier (null if none)
 *
 * Does NOT hold mutable battle state — that lives in BattleCombatant (a wrapper
 * used by the combat engine during a fight). This keeps the character definition
 * clean and reusable across multiple encounters.
 */
public abstract class Character extends Entity {

    private final CharacterStats    baseStats;
    private final CombatStats       combatStats;
    private final CharacterType     type;

    /**
     * Identifier of this character's innate cursed technique, e.g. "BLOOD_MANIPULATION".
     * Null if the character has no innate technique.
     */
    private final String innateTechiqueId;

    /**
     * The full pool of moves this character knows.
     * Guaranteed moves (Basic Punch, Basic Block) are always present.
     * All other moves have been validated against this character's stat prerequisites
     * and slot budget at construction time.
     */
    private final List<Move> knownMoves;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    protected Character(
        String          id,
        String          name,
        CharacterType   type,
        CharacterStats  baseStats,
        String          innateTechniqueId,
        List<Move>      knownMoves
    ) {
        super(id, name);
        Objects.requireNonNull(type,      "CharacterType cannot be null");
        Objects.requireNonNull(baseStats, "CharacterStats cannot be null");

        this.type              = type;
        this.baseStats         = baseStats;
        this.combatStats       = new CombatStats(baseStats);
        this.innateTechiqueId  = innateTechniqueId;
        this.knownMoves        = Collections.unmodifiableList(
            validateAndBuildMoveList(knownMoves, baseStats, combatStats, innateTechniqueId, type)
        );
    }

    // -------------------------------------------------------------------------
    // Move validation
    // -------------------------------------------------------------------------

    /**
     * Validates each move against:
     *  1. Prerequisite stats
     *  2. Technique restriction (requiredTechniqueId)
     *  3. CharacterType capability (e.g. HUMAN cannot use CE moves)
     *  4. Slot budget per category
     *
     * Guaranteed moves bypass slot counting.
     * Throws IllegalArgumentException if any move fails validation.
     */
    private static List<Move> validateAndBuildMoveList(
        List<Move>     moves,
        CharacterStats cs,
        CombatStats    combatStats,
        String         innateTechniqueId,
        CharacterType  type
    ) {
        if (moves == null) return List.of();

        // Track slot consumption per category
        Map<MoveCategory, Integer> slotUsed = new EnumMap<>(MoveCategory.class);

        List<Move> validated = new ArrayList<>();

        for (Move move : moves) {
            if (move.isGuaranteedMove()) {
                validated.add(move);
                continue;
            }

            // --- Prerequisite stats check ---
            for (Map.Entry<String, Integer> prereq : move.getPrerequisites().entrySet()) {
                int characterStatValue = getStatByName(cs, prereq.getKey());
                if (characterStatValue < prereq.getValue()) {
                    throw new IllegalArgumentException(
                        "Character does not meet prerequisite for move '" + move.getName()
                        + "': needs " + prereq.getKey() + " >= " + prereq.getValue()
                        + " but has " + characterStatValue
                    );
                }
            }

            // --- Technique restriction check ---
            if (move.getRequiredTechniqueId() != null) {
                if (!move.getRequiredTechniqueId().equals(innateTechniqueId)) {
                    throw new IllegalArgumentException(
                        "Character does not possess required technique '"
                        + move.getRequiredTechniqueId() + "' for move '" + move.getName() + "'"
                    );
                }
            }

            // --- CharacterType capability check ---
            validateTypeCaps(move, type);

            // --- Slot budget check ---
            // DEFENSIVE and UTILITY moves are not slot-gated — any character can equip them.
            MoveCategory cat = move.getCategory();
            if (cat != MoveCategory.DEFENSIVE && cat != MoveCategory.UTILITY) {
                int used      = slotUsed.getOrDefault(cat, 0);
                int available = getSlotCount(combatStats, cs, cat);
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

    private static void validateTypeCaps(Move move, CharacterType type) {
        MoveCategory cat = move.getCategory();
        if (type == CharacterType.HUMAN) {
            boolean hasCeComponent =
                cat == MoveCategory.INNATE_TECHNIQUE
                || cat == MoveCategory.NON_INNATE_TECHNIQUE
                || cat == MoveCategory.PHYSICAL_CURSED_ENERGY
                || cat == MoveCategory.PHYSICAL_INNATE_TECHNIQUE
                || cat == MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE
                || cat == MoveCategory.INNATE_NON_INNATE_TECHNIQUE
                || cat == MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;
            if (hasCeComponent) {
                throw new IllegalArgumentException(
                    "HUMAN characters cannot use CE-tagged moves. Move: " + move.getName()
                );
            }
        }
    }

    private static int getSlotCount(CombatStats combatStats, CharacterStats cs, MoveCategory cat) {
        return switch (cat) {
            case PHYSICAL -> combatStats.getPhysicalMoveSlots();
            case INNATE_TECHNIQUE -> combatStats.getCursedTechniqueSlots();
            case NON_INNATE_TECHNIQUE -> combatStats.getJujutsuTechniqueSlots();
            default -> combatStats.hybridSlots(cs, cat);
        };
    }

    private static int getStatByName(CharacterStats cs, String statName) {
        return switch (statName.toLowerCase()) {
            case "vitality"               -> cs.getVitality();
            case "strength"               -> cs.getStrength();
            case "durability"             -> cs.getDurability();
            case "speed"                  -> cs.getSpeed();
            case "cursedenergyreserves"   -> cs.getCursedEnergyReserves();
            case "cursedenergyefficiency" -> cs.getCursedEnergyEfficiency();
            case "cursedenergyoutput"     -> cs.getCursedEnergyOutput();
            case "jujutsuskill"           -> cs.getJujutsuSkill();
            case "combatability"          -> cs.getCombatAbility();
            case "cursedtechniquemastery" -> cs.getCursedTechniqueMastery();
            default -> throw new IllegalArgumentException("Unknown stat name: " + statName);
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public CharacterStats   getBaseStats()          { return baseStats; }
    public CombatStats      getCombatStats()         { return combatStats; }
    public CharacterType    getType()                { return type; }
    public String           getInnateTechniqueId()   { return innateTechiqueId; }
    public List<Move>       getKnownMoves()          { return knownMoves; }
    public boolean          hasInnateTechnique()     { return innateTechiqueId != null; }
}
