package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.util.*;

/**
 * DTO for serialising/deserialising a character to/from JSON.
 *
 * ID scheme: 6-digit zero-padded integer string, auto-assigned by CharacterRepository.
 *
 * Innate technique is stored as a plain name string (e.g. "Shrine").
 * Move pool is stored as a list of 6-digit move IDs referencing MoveRepository.
 * If a move ID cannot be resolved at load time, it is skipped with a warning.
 *
 * Abilities are stub objects (name + description only) for now.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CharacterData {

    public String id;       // 6-digit auto-assigned, e.g. "000000"
    public String name;
    /** Player-facing character flavour text shown on selection pages. */
    public String description = "";
    /**
     * Optional display-only title/epithet (e.g. "SHIKIGAMI", "The Honored One").
     * Null/blank when unset. Carries no combat rules — purely a flavour label for
     * roster and battle presentation. Absent on legacy saves (treated as unset).
     */
    public String title;
    /** Relative graphics resource path, e.g. {@code assets/sprites/characters/yuji_frontsprite.png}. */
    public String spriteAsset;

    /**
     * Stored {@link CharacterType} name (e.g. "SORCERER", "SHIKIGAMI").
     * A missing/blank value resolves to {@link CharacterType#SORCERER} (the
     * legacy default) via {@link CharacterType#fromStoredValue}. An unknown
     * non-blank value fails loudly at load time.
     */
    public String type;

    /**
     * Whether this definition can be picked directly from a fighter roster
     * (local selection, multiplayer summaries, challenge creation/acceptance).
     * Legacy sorcerers default to selectable; shikigami default to not
     * selectable (they are summoned, not chosen) unless an editor explicitly
     * flips this on. {@code null} resolves to the type-specific default via
     * {@link #effectiveSelectable()}.
     */
    public Boolean directlySelectable;

    /**
     * Base CE paid by this shikigami's summoner on each active resolution tick.
     * Required for {@link CharacterType#SHIKIGAMI} definitions and unset for
     * other character types.
     */
    public Double baseCeDrainPerTick;

    /**
     * Human-readable innate technique name (e.g. "Shrine", "Blood Manipulation").
     * Null means no innate technique.
     * Matched case-insensitively against Move.requiredTechniqueId when loading moves.
     * Will be replaced by a Technique ID reference once the Technique class is implemented.
     */
    public String innateTechniqueName;

    /**
     * Whether this character wields a weapon. Gates {@code weaponRequired} moves
     * (notably parries). Defaults to false.
     */
    public boolean hasWeapon = false;

    // --- Stats (all integers, range 10–300, baseline 80) ---
    public int vitality               = 80;
    public int strength               = 80;
    public int durability             = 80;
    public int speed                  = 80;
    public int cursedEnergyReserves   = 80;
    public int cursedEnergyEfficiency = 80;
    public int cursedEnergyOutput     = 80;
    public int jujutsuSkill           = 80;
    public int combatAbility          = 80;
    public int cursedTechniqueMastery = 80;

    /**
     * 6-digit move IDs from MoveRepository, in order of assignment.
     * Guaranteed moves (Basic Punch, Basic Block) should always be first.
     */
    public List<String> moveIds;

    /**
     * Move IDs unlocked by technique-tree nodes and available to learn. They do
     * not become known until also present in {@link #moveIds}.
     */
    public List<String> availableMoveIds;

    /**
     * 6-digit ability IDs assigned from the character's available pool.
     * Resolved to Ability domain objects at load time via toCharacter().
     */
    public List<String> abilityIds;

    /**
     * Ability IDs unlocked by technique-tree nodes and available for normal
     * assignment. They do not become active until also present in {@link #abilityIds}.
     */
    public List<String> availableAbilityIds;

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    /**
     * The resolved {@link CharacterType}. A missing/blank {@link #type} field
     * (legacy content) resolves to {@link CharacterType#SORCERER}; an unknown
     * non-blank value fails loudly.
     */
    public CharacterType effectiveType() {
        return CharacterType.fromStoredValue(type);
    }

    /**
     * The resolved direct-selectability. A {@code null} {@link #directlySelectable}
     * resolves to the type-specific default: sorcerers are selectable, shikigami
     * are not. An explicit value always wins.
     */
    public boolean effectiveSelectable() {
        if (directlySelectable != null) {
            return directlySelectable;
        }
        return effectiveType() != CharacterType.SHIKIGAMI;
    }

    public CharacterStats toCharacterStats() {
        return new CharacterStats.Builder()
            .vitality(vitality)
            .strength(strength)
            .durability(durability)
            .speed(speed)
            .cursedEnergyReserves(cursedEnergyReserves)
            .cursedEnergyEfficiency(cursedEnergyEfficiency)
            .cursedEnergyOutput(cursedEnergyOutput)
            .jujutsuSkill(jujutsuSkill)
            .combatAbility(combatAbility)
            .cursedTechniqueMastery(cursedTechniqueMastery)
            .build();
    }

    /** Compute derived combat stats for display purposes (no character object needed). */
    public CombatStats toCombatStats() {
        return new CombatStats(toCharacterStats());
    }

    /**
     * Build a Character domain object, resolving move IDs from the given repository.
     * Move IDs that don't exist in the repository are skipped with a console warning.
     */
    public Character toCharacter(MoveRepository moveRepo) {
        return toCharacter(moveRepo, null);
    }

    public Character toCharacter(MoveRepository moveRepo, AbilityRepository abilityRepo) {
        return toCharacter(moveRepo, abilityRepo, null);
    }

    public Character toCharacter(
        MoveRepository moveRepo,
        AbilityRepository abilityRepo,
        TechniqueRepository techniqueRepo
    ) {
        CharacterStats stats = toCharacterStats();
        List<Move> moves = new ArrayList<>();
        List<InnateTechniqueData> techniques = techniqueRepo == null ? null : techniqueRepo.getAll();
        if (techniques != null) {
            techniques.forEach(technique -> TechniqueSkillTree.synchronize(
                technique, moveRepo.getAll(), abilityRepo == null ? List.of() : abilityRepo.getAll()));
        }
        AbilityResolver.Result resolvedAbilities = AbilityResolver.resolve(
            this, abilityRepo, moveId -> moveId != null && moveRepo.findById(moveId)
                .map(move -> {
                    try {
                        move.toMove();
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .orElse(false), techniqueRepo);
        validateAbilityAssignments(abilityRepo, resolvedAbilities);
        validateStatAllocationMinimums(resolvedAbilities);
        validateStatAllocationMaximums(resolvedAbilities);
        List<Ability> abilities = resolvedAbilities.toDomainAbilities();
        validateDirectMoveAssignments(moveRepo, resolvedAbilities.availableMoveIds());
        Set<String> resolvedMoveIds = new LinkedHashSet<>();
        if (moveIds != null) resolvedMoveIds.addAll(moveIds);

        validateSelectedMoveNodes(moveRepo, techniques);

        for (String moveId : resolvedMoveIds) {
                if (moveId == null || moveId.isBlank()) {
                    System.err.println("[WARN] Blank move ID skipped for character '" + name + "'");
                    continue;
                }
                var found = moveRepo.findById(moveId);
                if (found.isPresent()) {
                    try {
                        moves.add(found.get().toMoveResolved(
                            launchId -> moveRepo.findById(launchId).orElse(null)));
                    } catch (Exception e) {
                        System.err.println("[WARN] Could not build move " + moveId + ": " + e.getMessage());
                    }
                } else {
                    System.err.println("[WARN] Move ID " + moveId + " not found in repository — skipped for character '" + name + "'");
                }
        }

        Character character = constructTypedCharacter(stats, moves, abilities);
        character.setTitle(title);
        return character;
    }

    /**
     * Build the correct concrete {@link Character} subclass for this definition's
     * {@link #effectiveType()}. Centralized so {@link #toCharacter} and the
     * server-side {@code ContentCatalog} build the same subclass for a given type.
     */
    public Character constructTypedCharacter(CharacterStats stats, List<Move> moves, List<Ability> abilities) {
        CharacterType resolved = effectiveType();
        if (resolved == CharacterType.SHIKIGAMI) {
            if (baseCeDrainPerTick == null || !Double.isFinite(baseCeDrainPerTick)
                || baseCeDrainPerTick <= 0.0) {
                throw new IllegalArgumentException(
                    "Shikigami base CE drain per tick must be greater than 0");
            }
            return new ShikigamiCharacter(id, name, stats, innateTechniqueName,
                moves, abilities, hasWeapon, baseCeDrainPerTick);
        }
        return new SorcererCharacter(id, name, stats, innateTechniqueName, moves, abilities, hasWeapon);
    }

    private void validateSelectedMoveNodes(
        MoveRepository moveRepo,
        List<InnateTechniqueData> techniques
    ) {
        if (moveIds == null || techniques == null) return;
        for (String moveId : moveIds) {
            MoveData move = moveRepo.findById(moveId).orElse(null);
            if (move == null || move.requiredTechniqueId == null) continue;
            InnateTechniqueData technique = TechniqueSkillTree.techniqueByName(
                techniques, move.requiredTechniqueId);
            if (technique == null) continue;
            SkillTreeNodeData node = TechniqueSkillTree.nodeForContent(
                technique, SkillTreeNodeData.MOVE, moveId);
            if (node != null && (!TechniqueSkillTree.isActive(node, this)
                || !TechniqueSkillTree.isUnlocked(technique, node, this))) {
                throw new IllegalArgumentException(
                    "Technique-tree move is not unlocked for " + move.name);
            }
        }
    }

    private void validateDirectMoveAssignments(
        MoveRepository moveRepo,
        List<String> availableMoves
    ) {
        if (moveIds == null) return;
        for (String moveId : moveIds) {
            MoveData move = moveRepo.findById(moveId).orElse(null);
            if (move != null && move.mustBeGranted
                && (availableMoves == null || !availableMoves.contains(moveId))) {
                throw new IllegalArgumentException(
                    "Move '" + move.name
                        + "' must be granted before it can be learned");
            }
        }
    }

    private void validateAbilityAssignments(
        AbilityRepository abilityRepo,
        AbilityResolver.Result resolved
    ) {
        if (abilityRepo == null || abilityIds == null) return;
        for (String abilityId : abilityIds) {
            AbilityData ability = abilityRepo.findById(abilityId).orElse(null);
            if (ability != null && !resolved.containsAbility(abilityId)) {
                throw new IllegalArgumentException(
                    "Ability '" + ability.name + "' is not available to this character");
            }
        }
    }

    /** Validate editor-only stat floors supplied by assigned passive abilities. */
    public void validateStatAllocationMinimums(AbilityResolver.Result resolved) {
        if (resolved == null) return;
        for (Map.Entry<StatKey, Integer> entry
            : resolved.statAllocationMinimums().entrySet()) {
            int actual = entry.getKey().get(this);
            if (actual < entry.getValue()) {
                throw new IllegalArgumentException(
                    entry.getKey().label + " must be at least " + entry.getValue()
                        + " because of an assigned ability (currently " + actual + ")");
            }
        }
    }

    /** Validate editor-only stat ceilings supplied by assigned passive abilities. */
    public void validateStatAllocationMaximums(AbilityResolver.Result resolved) {
        if (resolved == null) return;
        for (Map.Entry<StatKey, Integer> entry
            : resolved.statAllocationMaximums().entrySet()) {
            int actual = entry.getKey().get(this);
            if (actual > entry.getValue()) {
                throw new IllegalArgumentException(
                    entry.getKey().label + " must be at most " + entry.getValue()
                        + " because of an assigned ability (currently " + actual + ")");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Conversion: CharacterData → CharacterData (from domain Character)
    // -------------------------------------------------------------------------

    public static CharacterData fromCharacter(Character character) {
        CharacterData d = new CharacterData();
        d.id                    = character.getId();
        d.name                  = character.getName();
        d.title                 = character.getTitle();
        // The domain Character carries its type but not the authoring-only
        // directlySelectable flag; leave the DTO flag null so the type default
        // applies (sorcerers selectable, shikigami not). The editor sets the
        // explicit value when an author overrides the default.
        d.type                  = character.getType() == null
            ? null : character.getType().name();
        if (character.getType() == CharacterType.SHIKIGAMI) {
            d.baseCeDrainPerTick = character.getBaseCeDrainPerTick();
        }
        d.innateTechniqueName   = character.getInnateTechniqueName();
        d.hasWeapon             = character.hasWeapon();

        CharacterStats cs = character.getBaseStats();
        d.vitality               = cs.getVitality();
        d.strength               = cs.getStrength();
        d.durability             = cs.getDurability();
        d.speed                  = cs.getSpeed();
        d.cursedEnergyReserves   = cs.getCursedEnergyReserves();
        d.cursedEnergyEfficiency = cs.getCursedEnergyEfficiency();
        d.cursedEnergyOutput     = cs.getCursedEnergyOutput();
        d.jujutsuSkill           = cs.getJujutsuSkill();
        d.combatAbility          = cs.getCombatAbility();
        d.cursedTechniqueMastery = cs.getCursedTechniqueMastery();

        d.moveIds = character.getKnownMoves().stream()
            .map(Move::getId)
            .toList();
        d.abilityIds = character.getAbilities().stream()
            .map(Ability::getId)
            .toList();

        return d;
    }
}
