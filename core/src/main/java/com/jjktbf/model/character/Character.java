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
    private final Set<String> forcedMoveIds;

    /**
     * Whether this character wields a weapon. A move flagged
     * {@code weaponRequired} (notably every {@link com.jjktbf.model.move.DefenseType#PARRY})
     * can only be learned/used by a character with a weapon.
     */
    private final boolean hasWeapon;

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
        this(id, name, type, baseStats, innateTechniqueName, knownMoves, abilities,
             accessibleTechniquesOf(innateTechniqueName, abilities), false);
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
     *
     * @param hasWeapon whether this character wields a weapon (gates
     *                  {@code weaponRequired} moves such as parries)
     */
    protected Character(
        String         id,
        String         name,
        CharacterType  type,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        boolean        hasWeapon
    ) {
        super(id, name);
        Objects.requireNonNull(type,      "CharacterType cannot be null");
        Objects.requireNonNull(baseStats, "CharacterStats cannot be null");

        this.type               = type;
        this.baseStats          = baseStats;
        this.combatStats        = new CombatStats(baseStats);
        this.innateTechniqueName = innateTechniqueName;
        this.hasWeapon          = hasWeapon;
        Set<String> availableMoveIds = availableMoveIdsOf(abilities);
        Set<String> forcedMoves = forcedMoveIdsOf(abilities);
        List<Move> validatedMoves = validateAndBuildMoveList(
            knownMoves, baseStats, combatStats, accessibleTechniques,
            availableMoveIds, forcedMoves, lockedMoveTagsOf(abilities), hasWeapon);
        validateCodedMoveReferences(validatedMoves);
        this.knownMoves = Collections.unmodifiableList(validatedMoves);
        this.forcedMoveIds = Collections.unmodifiableSet(forcedMoves);
        this.abilities          = abilities != null
            ? Collections.unmodifiableList(new ArrayList<>(abilities)) : List.of();
    }

    private static void validateCodedMoveReferences(List<Move> moves) {
        Set<String> knownIds = new HashSet<>();
        for (Move move : moves) knownIds.add(move.getId());
        for (Move move : moves) {
            List<com.jjktbf.model.move.StatusEffect> effects = new ArrayList<>(move.getOnHitEffects());
            effects.addAll(move.getSelfEffects());
            for (var effect : effects) {
                String target = effect.getCodedTarget();
                if (!effect.isCoded() || target == null || !target.matches("\\d{6}")) continue;
                if (!knownIds.contains(target)) {
                    throw new IllegalArgumentException(
                        "Move '" + move.getName() + "' references unknown move " + target);
                }
            }
        }
    }

    /**
     * Resolve the set of technique names a character can use moves from: their
     * innate technique plus any technique granted by an {@code UNLOCK_TECHNIQUE}
     * ability effect. Case-insensitive (names are lower-cased on insertion).
     */
    static java.util.Set<String> accessibleTechniquesOf(
            String innateTechniqueName, List<Ability> abilities) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (innateTechniqueName != null && !innateTechniqueName.isBlank()) {
            set.add(innateTechniqueName.toLowerCase());
        }
        if (abilities != null) {
            for (Ability a : abilities) {
                if (!a.isPassive() || !a.isAlwaysActive()) continue;
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

    /** Moves made available by passives or represented by a queued active ability. */
    private static java.util.Set<String> availableMoveIdsOf(List<Ability> abilities) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (abilities == null) return ids;
        for (Ability ability : abilities) {
            if (ability.isActive()
                && "QUEUED".equalsIgnoreCase(ability.getActiveSubType())
                && ability.getActiveMoveId() != null
                && !ability.getActiveMoveId().isBlank()) {
                ids.add(ability.getActiveMoveId());
            }
            if (!ability.isPassive() || !ability.isAlwaysActive()) continue;
            for (AbilityEffectData effect : ability.getEffects()) {
                if ((AbilityEffectType.GRANT_MOVE.name().equalsIgnoreCase(effect.type)
                    || AbilityEffectType.FORCE_MOVE.name().equalsIgnoreCase(effect.type))
                    && effect.moveId != null && !effect.moveId.isBlank()) {
                    ids.add(effect.moveId);
                }
            }
        }
        return ids;
    }

    /** Moves automatically learned and exempt from normal assignment rules. */
    private static java.util.Set<String> forcedMoveIdsOf(List<Ability> abilities) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (abilities == null) return ids;
        for (Ability ability : abilities) {
            if (ability.isActive()
                && "QUEUED".equalsIgnoreCase(ability.getActiveSubType())
                && ability.getActiveMoveId() != null
                && !ability.getActiveMoveId().isBlank()) {
                ids.add(ability.getActiveMoveId());
            }
            if (!ability.isPassive() || !ability.isAlwaysActive()) continue;
            for (AbilityEffectData effect : ability.getEffects()) {
                if (AbilityEffectType.FORCE_MOVE.name().equalsIgnoreCase(effect.type)
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
            if (!ability.isPassive() || !ability.isAlwaysActive()) continue;
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
        java.util.Set<String> availableMoveIds,
        java.util.Set<String> forcedMoveIds,
        java.util.Set<String> lockedMoveTags,
        boolean        hasWeapon
    ) {
        if (moves == null) return List.of();

        Map<MovePool, Integer> slotUsed = new EnumMap<>(MovePool.class);
        List<Move> validated = new ArrayList<>();

        for (Move move : moves) {
            boolean moveAvailable = availableMoveIds != null
                && availableMoveIds.contains(move.getId());
            boolean forced = forcedMoveIds != null && forcedMoveIds.contains(move.getId());

            if (move.mustBeGranted() && !moveAvailable) {
                throw new IllegalArgumentException(
                    "Move '" + move.getName() + "' must be granted by an ability");
            }

            // --- 0. Weapon requirement ---
            // A weaponRequired move (notably every parry) needs a weapon-wielding
            // character. Forced moves (ability-granted) bypass this like the other
            // restrictions, so an ability can still bestow a weapon technique.
            if (!forced && move.isWeaponRequired() && !hasWeapon) {
                throw new IllegalArgumentException(
                    "Move '" + move.getName() + "' requires a weapon but character '"
                    + "' does not have one");
            }

            if (!forced && lockedMoveTags != null
                && lockedMoveTags.stream().anyMatch(move::hasTag)) {
                throw new IllegalArgumentException(
                    "Ability restrictions prevent learning move '" + move.getName() + "'");
            }

            // --- 1. Prerequisite stats ---
            if (!forced) {
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
            if (!forced && move.getRequiredTechniqueId() != null) {
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
            if (!forced && !move.isFreeMove()) {
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
    public Set<String>     getForcedMoveIds()         { return forcedMoveIds; }
    public boolean         hasInnateTechnique()      { return innateTechniqueName != null; }
    public boolean         hasWeapon()                { return hasWeapon; }
}
