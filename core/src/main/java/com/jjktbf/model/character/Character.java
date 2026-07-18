package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MovePool;

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
 *   Moves with a requiredTechniqueId are only accessible to characters whose
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
     * Will be replaced by a Technique ID reference once the Technique class is implemented.
     */
    private final String innateTechniqueName;

    /**
     * The full pool of moves this character knows.
     * Guaranteed moves (Basic Punch, Basic Block) are always present.
     * All other moves are validated against stat prerequisites, technique
     * possession, and slot budget at construction time.
     */
    private final List<Move> knownMoves;
    private final List<Ability> abilities;

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
        this(id, name, type, baseStats, innateTechniqueName, knownMoves, List.of());
    }

    protected Character(
        String         id,
        String         name,
        CharacterType  type,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities
    ) {
        this(id, name, type, baseStats, innateTechniqueName, knownMoves, abilities, accessibleTechniquesOf(innateTechniqueName, abilities));
    }

    /**
     * Full construction with an explicit set of accessible technique names.
     *
     * <p>A character "has access to" technique T if it is their
     * {@code innateTechniqueName} OR an applied ability's
     * {@code UNLOCK_TECHNIQUE} effect grants it. The caller resolves this set
     * (typically {@link CharacterData#toCharacter}); the default constructors
     * compute it via {@link #accessibleTechniquesOf} from the ability list.
     *
     * <p>Move validation checks membership against this set instead of a single
     * {@code equalsIgnoreCase} against the innate name — which is what makes
     * UNLOCK_TECHNIQUE (and, by extension, Copy) functional.
     */
    protected Character(
        String         id,
        String         name,
        CharacterType  type,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques
    ) {
        super(id, name);
        Objects.requireNonNull(type,      "CharacterType cannot be null");
        Objects.requireNonNull(baseStats, "CharacterStats cannot be null");

        this.type               = type;
        this.baseStats          = baseStats;
        this.combatStats        = new CombatStats(baseStats);
        this.innateTechniqueName = innateTechniqueName;
        this.knownMoves         = Collections.unmodifiableList(
            validateAndBuildMoveList(
                knownMoves, baseStats, combatStats, accessibleTechniques,
                grantedMoveIdsOf(abilities), lockedMoveTagsOf(abilities))
        );
        this.abilities          = abilities != null
            ? Collections.unmodifiableList(new ArrayList<>(abilities)) : List.of();
    }

    /**
     * Resolve the set of technique names a character can use moves from: their
     * innate technique plus any technique granted by an {@code UNLOCK_TECHNIQUE}
     * ability effect. Case-insensitive (names are lower-cased on insertion).
     */
    private static java.util.Set<String> accessibleTechniquesOf(
            String innateTechniqueName, List<Ability> abilities) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (innateTechniqueName != null && !innateTechniqueName.isBlank()) {
            set.add(innateTechniqueName.toLowerCase());
        }
        if (abilities != null) {
            for (Ability a : abilities) {
                if (!a.isPassive()) continue;
                var effects = a.getEffects();
                if (effects == null) continue;
                for (var e : effects) {
                    if (com.jjktbf.model.character.AbilityEffectType.UNLOCK_TECHNIQUE.name().equalsIgnoreCase(e.type)
                        && e.stringValue != null && !e.stringValue.isBlank()) {
                        set.add(e.stringValue.toLowerCase());
                    }
                }
            }
        }
        return set;
    }

    /** Moves granted by passives or represented by a queued active ability. */
    private static java.util.Set<String> grantedMoveIdsOf(List<Ability> abilities) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (abilities == null) return ids;
        for (Ability ability : abilities) {
            if (ability.isActive()
                && "QUEUED".equalsIgnoreCase(ability.getActiveSubType())
                && ability.getActiveMoveId() != null
                && !ability.getActiveMoveId().isBlank()) {
                ids.add(ability.getActiveMoveId());
            }
            if (!ability.isPassive()) continue;
            for (AbilityEffectData effect : ability.getEffects()) {
                if (AbilityEffectType.GRANT_MOVE.name().equalsIgnoreCase(effect.type)
                    && effect.moveId != null && !effect.moveId.isBlank()) {
                    ids.add(effect.moveId);
                }
            }
        }
        return ids;
    }

    private static java.util.Set<String> lockedMoveTagsOf(List<Ability> abilities) {
        java.util.Set<String> tags = new java.util.HashSet<>();
        if (abilities == null) return tags;
        for (Ability ability : abilities) {
            if (!ability.isPassive()) continue;
            for (AbilityEffectData effect : ability.getEffects()) {
                if (AbilityEffectType.LOCK_MOVE_TAG.name().equalsIgnoreCase(effect.type)
                    && effect.moveTag != null && !effect.moveTag.isBlank()) {
                    tags.add(effect.moveTag);
                }
            }
        }
        return tags;
    }

    // -------------------------------------------------------------------------
    // Move validation
    // -------------------------------------------------------------------------

    private static List<Move> validateAndBuildMoveList(
        List<Move>     moves,
        CharacterStats cs,
        CombatStats    combatStats,
        java.util.Set<String> accessibleTechniques,
        java.util.Set<String> grantedMoveIds,
        java.util.Set<String> lockedMoveTags
    ) {
        if (moves == null) return List.of();

        Map<MovePool, Integer> slotUsed = new EnumMap<>(MovePool.class);
        List<Move> validated = new ArrayList<>();

        for (Move move : moves) {
            boolean granted = grantedMoveIds != null && grantedMoveIds.contains(move.getId());

            if (!granted && lockedMoveTags != null
                && lockedMoveTags.stream().anyMatch(move::hasTag)) {
                throw new IllegalArgumentException(
                    "Ability restrictions prevent learning move '" + move.getName() + "'");
            }

            // --- 1. Prerequisite stats ---
            if (!granted) {
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
            }

            // --- 2. Technique restriction ---
            // A move is usable if its required technique is the character's
            // innate technique OR was granted by an UNLOCK_TECHNIQUE ability
            // effect (e.g. Six Eyes → Limitless, or a Copy ability). Case-insensitive.
            if (!granted && move.getRequiredTechniqueId() != null) {
                if (accessibleTechniques == null
                    || !accessibleTechniques.contains(move.getRequiredTechniqueId().toLowerCase())) {
                    throw new IllegalArgumentException(
                        "Character does not possess required technique '"
                        + move.getRequiredTechniqueId()
                        + "' for move '" + move.getName() + "'"
                    );
                }
            }

            // --- 3. Slot budget — only free moves are exempt ---
            // Every non-free move consumes a slot in its pool (Combat Arts or
            // Jujutsu Arts), regardless of whether it is offensive, defensive,
            // or utility.
            if (!granted && !move.isFreeMove()) {
                MovePool pool = move.getPool();
                int used      = slotUsed.getOrDefault(pool, 0);
                int available = SlotBudgetEnforcer.slotBudgetFor(combatStats, pool);
                if (used >= available) {
                    throw new IllegalArgumentException(
                        "Character has no available slots for pool " + pool
                        + " (budget=" + available + ") when trying to add move '" + move.getName() + "'"
                    );
                }
                slotUsed.put(pool, used + 1);
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
    public List<Ability>   getAbilities()            { return abilities; }
    public boolean         hasInnateTechnique()      { return innateTechniqueName != null; }
}
