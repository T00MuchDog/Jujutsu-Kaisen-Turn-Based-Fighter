package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;
import com.jjktbf.model.weapon.CursedToolData;
import com.jjktbf.model.weapon.CursedToolRepository;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

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
    /** Relative graphics resource path, e.g. {@code assets/sprites/characters/yuji_frontsprite.png}. */
    public String spriteAsset;

    /**
     * Stored {@link CharacterType} name (e.g. "SORCERER", "CURSED_SPIRIT").
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
     * {@link com.jjktbf.model.weapon.WeaponType} names of the base (non-cursed-tool)
     * weapons this character has equipped. A move carrying a weapon-type tag can
     * only be learned while the matching weapon type is equipped from this list
     * or via an equipped cursed tool. A character may equip multiple weapons.
     */
    public List<String> equippedWeaponTypes;

    /**
     * 6-digit {@link CursedToolData} ids of the cursed tools this character has
     * equipped. Cursed tools satisfy the weapon gate for their weapon type AND
     * waive that type's jujutsu stat prerequisites and cursed energy costs.
     * May be null/empty.
     */
    public List<String> equippedCursedToolIds;

    /**
     * Compat reader: the legacy single "has a weapon" boolean became the
     * multi-weapon equipment lists. Legacy weapon wielders are all katana
     * users, so the old flag translates to an equipped Katana.
     */
    @JsonProperty("hasWeapon")
    private void readHasWeapon(boolean hasWeapon) {
        if (hasWeapon && (equippedWeaponTypes == null || equippedWeaponTypes.isEmpty())) {
            equippedWeaponTypes = new ArrayList<>(List.of("KATANA"));
        }
    }

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
        return toCharacter(moveRepo, abilityRepo, techniqueRepo, null);
    }

    /**
     * Full domain build, additionally resolving equipped weapons and cursed
     * tools from {@code cursedToolRepo}. Pass {@code null} to ignore equipment
     * (tool references are then skipped entirely, which suits unit tests).
     */
    public Character toCharacter(
        MoveRepository moveRepo,
        AbilityRepository abilityRepo,
        TechniqueRepository techniqueRepo,
        CursedToolRepository cursedToolRepo
    ) {
        CharacterStats stats = toCharacterStats();
        List<Move> moves = new ArrayList<>();
        List<InnateTechniqueData> techniques = techniqueRepo == null ? null : techniqueRepo.getAll();
        if (techniques != null) {
            techniques.forEach(technique -> TechniqueSkillTree.synchronize(
                technique, moveRepo.getAll(), abilityRepo == null ? List.of() : abilityRepo.getAll()));
        }
        Equipment prerequisiteEquipment = resolveEquipment(
            moveRepo, abilityRepo, cursedToolRepo);
        BiPredicate<SkillTreeNodeData, StatKey> prerequisiteWaiver =
            cursedToolPrerequisiteWaiver(moveRepo, prerequisiteEquipment);
        Equipment equipment = prerequisiteEquipment.filterGrantedMoves(
            move -> TechniqueSkillTree.allowsMove(
                techniques, move.getRequiredTechniqueId(), move.getId(), this,
                prerequisiteWaiver));
        Set<String> resolvedMoveIds = new LinkedHashSet<>();
        if (moveIds != null) resolvedMoveIds.addAll(moveIds);

        validateSelectedMoveNodes(moveRepo, techniques, prerequisiteWaiver);

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

        ResolvedCharacter resolved = resolveEquipmentContent(
            stats,
            moves,
            equipment,
            learnedToolMoveIds -> AbilityResolver.resolve(
                this, abilityRepo, moveId -> moveId != null && moveRepo.findById(moveId)
                    .map(move -> {
                        try {
                            move.toMove();
                            return true;
                        } catch (Exception ex) {
                            return false;
                        }
                    })
                    .orElse(false), techniqueRepo, learnedToolMoveIds,
                equipment.grantedMoveIds()));
        AbilityResolver.Result resolvedAbilities = resolved.abilities();
        validateAbilityAssignments(abilityRepo, resolvedAbilities);
        validateStatAllocationMinimums(resolvedAbilities);
        validateStatAllocationMaximums(resolvedAbilities);
        Set<String> availableMoveIds = new LinkedHashSet<>(resolvedAbilities.availableMoveIds());
        availableMoveIds.addAll(equipment.grantedMoveIds());
        validateDirectMoveAssignments(moveRepo, new ArrayList<>(availableMoveIds));
        return resolved.character();
    }

    /**
     * Resolve this character's {@link Equipment} (base weapons plus cursed
     * tools). Fails loudly on unknown weapon types and unknown tool ids.
     */
    public Equipment resolveEquipment(
        MoveRepository moveRepo,
        AbilityRepository abilityRepo,
        CursedToolRepository cursedToolRepo
    ) {
        boolean hasCursedTool = equippedCursedToolIds != null
            && equippedCursedToolIds.stream()
                .anyMatch(toolId -> toolId != null && !toolId.isBlank());
        if (!hasCursedTool) {
            return Equipment.resolve(
                equippedWeaponTypes,
                equippedCursedToolIds,
                cursedToolRepo == null ? List.of() : cursedToolRepo.getAll(),
                List.of());
        }
        List<Move> allMoves = new ArrayList<>();
        for (MoveData move : moveRepo.getAll()) {
            try {
                allMoves.add(move.toMoveResolved(
                    launchId -> moveRepo.findById(launchId).orElse(null)));
            } catch (RuntimeException exception) {
                System.err.println("[WARN] Invalid move " + move.id
                    + " cannot be granted by equipment: " + exception.getMessage());
            }
        }
        return Equipment.resolve(
            equippedWeaponTypes,
            equippedCursedToolIds,
            cursedToolRepo == null ? List.of() : cursedToolRepo.getAll(),
            allMoves);
    }

    /**
     * Build the correct concrete {@link Character} subclass for this definition's
     * {@link #effectiveType()}. Centralized so {@link #toCharacter} and the
     * server-side {@code ContentCatalog} build the same subclass for a given type.
     */
    public Character constructTypedCharacter(CharacterStats stats, List<Move> moves, List<Ability> abilities) {
        return constructTypedCharacter(stats, moves, abilities, Equipment.NONE);
    }

    /**
     * As {@link #constructTypedCharacter(CharacterStats, List, List)}, with the
     * character's resolved {@link Equipment} (weapons and cursed tools).
     */
    public Character constructTypedCharacter(
        CharacterStats stats,
        List<Move> moves,
        List<Ability> abilities,
        Equipment equipment
    ) {
        CharacterType resolved = effectiveType();
        return switch (resolved) {
            case SHIKIGAMI -> {
                if (baseCeDrainPerTick == null || !Double.isFinite(baseCeDrainPerTick)
                    || baseCeDrainPerTick <= 0.0) {
                    throw new IllegalArgumentException(
                        "Shikigami base CE drain per tick must be greater than 0");
                }
                yield new ShikigamiCharacter(id, name, stats, innateTechniqueName,
                    moves, abilities, equipment, baseCeDrainPerTick);
            }
            case CURSED_SPIRIT -> new CursedSpiritCharacter(
                id, name, stats, innateTechniqueName, moves, abilities, equipment);
            case CURSED_CORPSE -> new CursedCorpseCharacter(
                id, name, stats, innateTechniqueName, moves, abilities, equipment);
            case SORCERER -> new SorcererCharacter(
                id, name, stats, innateTechniqueName, moves, abilities, equipment);
        };
    }

    /** Character plus the stable ability result used to construct it. */
    public record ResolvedCharacter(Character character, AbilityResolver.Result abilities) { }

    /**
     * Resolve move-sourced abilities and automatic equipment moves to a stable state.
     * A derived tool move must pass character validation before it can source an ability.
     */
    public ResolvedCharacter resolveEquipmentContent(
        CharacterStats stats,
        List<Move> selectedMoves,
        Equipment equipment,
        Function<Set<String>, AbilityResolver.Result> abilityResolver
    ) {
        Objects.requireNonNull(abilityResolver, "Ability resolver cannot be null");
        Equipment resolvedEquipment = equipment == null ? Equipment.NONE : equipment;
        Set<String> toolMoveIds = resolvedEquipment.grantedMoveIds();
        Set<String> learnedToolMoveIds = Set.of();
        Set<Set<String>> seen = new HashSet<>();
        while (seen.add(learnedToolMoveIds)) {
            AbilityResolver.Result abilities = abilityResolver.apply(learnedToolMoveIds);
            Character character = constructTypedCharacter(
                stats, selectedMoves, abilities.toDomainAbilities(), resolvedEquipment);
            Set<String> nextLearnedToolMoveIds = character.getKnownMoves().stream()
                .map(Move::getId)
                .filter(toolMoveIds::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (nextLearnedToolMoveIds.equals(learnedToolMoveIds)) {
                return new ResolvedCharacter(character, abilities);
            }
            learnedToolMoveIds = Set.copyOf(nextLearnedToolMoveIds);
        }
        throw new IllegalArgumentException(
            "Cursed-tool moves and their move-sourced abilities do not resolve consistently");
    }

    private void validateSelectedMoveNodes(
        MoveRepository moveRepo,
        List<InnateTechniqueData> techniques,
        BiPredicate<SkillTreeNodeData, StatKey> prerequisiteWaiver
    ) {
        if (moveIds == null || techniques == null) return;
        for (String moveId : moveIds) {
            MoveData move = moveRepo.findById(moveId).orElse(null);
            if (move == null || move.requiredTechniqueId == null) continue;
            if (!TechniqueSkillTree.allowsMove(
                techniques, move.requiredTechniqueId, moveId, this,
                prerequisiteWaiver)) {
                throw new IllegalArgumentException(
                    "Technique-tree move is not unlocked for " + move.name);
            }
        }
    }

    private static BiPredicate<SkillTreeNodeData, StatKey> cursedToolPrerequisiteWaiver(
        MoveRepository moveRepo,
        Equipment equipment
    ) {
        return (node, stat) -> {
            if (node == null || stat == null || !stat.isJujutsuPrerequisite()
                || !SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
                return false;
            }
            MoveData move = moveRepo.findById(node.contentId).orElse(null);
            return move != null && equipment.coversWeaponTags(move.weaponTags());
        };
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
        Equipment equipment     = character.getEquipment();
        d.equippedWeaponTypes   = equipment.baseTypes().isEmpty()
            ? null : new ArrayList<>(equipment.baseTypes().stream()
                .map(Enum::name).toList());
        d.equippedCursedToolIds = equipment.cursedToolIds().isEmpty()
            ? null : new ArrayList<>(equipment.cursedToolIds());

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

        Set<String> automaticMoveIds = equipment.grantedMoveIds();
        d.moveIds = character.getKnownMoves().stream()
            .filter(move -> !automaticMoveIds.contains(move.getId()))
            .map(Move::getId)
            .toList();
        d.abilityIds = character.getAbilities().stream()
            .filter(ability -> !"CURSED_TOOL".equalsIgnoreCase(ability.getSourceType()))
            .map(Ability::getId)
            .toList();

        return d;
    }
}
