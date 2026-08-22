package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.weapon.CursedToolData;
import com.jjktbf.model.weapon.WeaponType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything a character has equipped: base weapons plus cursed tools, and the
 * moves/abilities the equipped cursed tools bestow.
 *
 * <ul>
 *   <li>{@link #weaponTypes()} — every weapon type the character wields, from
 *       base weapons and cursed tools alike. Gates weapon-tagged moves.</li>
 *   <li>{@link #cursedToolTypes()} — the subset provided by cursed tools. Moves
 *       of these types cost the wielder no cursed energy.</li>
 *   <li>{@link #grantedMoves()} / {@link #grantedAbilities()} — content bestown
 *       by equipped tools, active while the tool stays equipped.</li>
 * </ul>
 *
 * Resolved from {@link CharacterData} by {@link #resolve}; immutable afterwards.
 */
public final class Equipment {

    /** No equipment at all: no weapons, no tools, no granted content. */
    public static final Equipment NONE = new Equipment(
        Set.of(), Set.of(), List.of(), List.of(), List.of());

    /** Equipment carrying only base weapons (no cursed tools, no granted content). */
    public static Equipment base(WeaponType... types) {
        return new Equipment(Set.of(types), Set.of(), List.of(), List.of(), List.of());
    }

    /** Equipment carrying a single cursed tool of the given type, granting nothing. */
    public static Equipment cursedTool(WeaponType type) {
        return new Equipment(Set.of(), Set.of(type), List.of(), List.of(), List.of());
    }

    private final Set<WeaponType> baseTypes;
    private final Set<WeaponType> cursedToolTypes;
    private final List<String> cursedToolIds;
    private final List<Move> grantedMoves;
    private final List<Ability> grantedAbilities;

    private Equipment(
        Set<WeaponType> baseTypes,
        Set<WeaponType> cursedToolTypes,
        List<String> cursedToolIds,
        List<Move> grantedMoves,
        List<Ability> grantedAbilities
    ) {
        this.baseTypes        = Set.copyOf(baseTypes);
        this.cursedToolTypes  = Set.copyOf(cursedToolTypes);
        this.cursedToolIds    = List.copyOf(cursedToolIds);
        this.grantedMoves     = List.copyOf(grantedMoves);
        this.grantedAbilities = List.copyOf(grantedAbilities);
    }

    /**
     * Resolve equipment from raw DTO inputs.
     *
     * @param equippedWeaponTypeNames stored {@link WeaponType} names of base
     *                                weapons (may be null/empty)
     * @param equippedCursedToolIds   6-digit {@link CursedToolData} ids; every
     *                                id must resolve in {@code allTools}
     * @param allTools                every known cursed tool definition
     * @param moveResolver            builds a domain {@link Move} from a move
     *                                id, or null when unknown
     * @param abilityResolver         builds a domain {@link Ability} from an
     *                                ability id, or null when unknown
     * @throws IllegalArgumentException on unknown weapon type names, unknown
     *                                  tool ids, or unresolvable granted content
     */
    public static Equipment resolve(
        List<String> equippedWeaponTypeNames,
        List<String> equippedCursedToolIds,
        List<CursedToolData> allTools,
        Function<String, Move> moveResolver,
        Function<String, Ability> abilityResolver
    ) {
        Set<WeaponType> baseTypes = new LinkedHashSet<>();
        if (equippedWeaponTypeNames != null) {
            for (String stored : equippedWeaponTypeNames) {
                WeaponType type = WeaponType.fromStoredValue(stored);
                if (type == null) {
                    throw new IllegalArgumentException(
                        "Unknown equipped weapon type '" + stored + "'");
                }
                baseTypes.add(type);
            }
        }

        Map<String, CursedToolData> toolsById = new LinkedHashMap<>();
        if (allTools != null) {
            for (CursedToolData tool : allTools) {
                if (tool != null) toolsById.put(tool.id, tool);
            }
        }
        List<String> toolIds = new ArrayList<>();
        Set<WeaponType> toolTypes = new LinkedHashSet<>();
        if (equippedCursedToolIds != null) {
            for (String toolId : equippedCursedToolIds) {
                if (toolId == null || toolId.isBlank()) continue;
                CursedToolData tool = toolsById.get(toolId);
                if (tool == null) {
                    throw new IllegalArgumentException(
                        "Equipped cursed tool " + toolId + " does not exist");
                }
                toolIds.add(toolId);
                toolTypes.add(tool.effectiveWeaponType());
            }
        }

        List<Move> grantedMoves = new ArrayList<>();
        List<Ability> grantedAbilities = new ArrayList<>();
        for (String toolId : toolIds) {
            CursedToolData tool = toolsById.get(toolId);
            if (tool.grantedMoveIds != null) {
                for (String moveId : tool.grantedMoveIds) {
                    if (moveId == null || moveId.isBlank()) continue;
                    Move move = moveResolver.apply(moveId);
                    if (move == null) {
                        throw new IllegalArgumentException(
                            "Cursed tool '" + tool.name + "' grants unknown move " + moveId);
                    }
                    grantedMoves.add(move);
                }
            }
            if (tool.grantedAbilityIds != null) {
                for (String abilityId : tool.grantedAbilityIds) {
                    if (abilityId == null || abilityId.isBlank()) continue;
                    Ability ability = abilityResolver.apply(abilityId);
                    if (ability == null) {
                        throw new IllegalArgumentException(
                            "Cursed tool '" + tool.name
                                + "' grants unknown ability " + abilityId);
                    }
                    grantedAbilities.add(ability);
                }
            }
        }
        return new Equipment(baseTypes, toolTypes, toolIds, grantedMoves, grantedAbilities);
    }

    /** Every wielded weapon type — base weapons and cursed tools alike. */
    public Set<WeaponType> weaponTypes() {
        Set<WeaponType> all = new LinkedHashSet<>(baseTypes);
        all.addAll(cursedToolTypes);
        return Collections.unmodifiableSet(all);
    }

    /** Weapon types the character wields as base (non-cursed-tool) weapons. */
    public Set<WeaponType> baseTypes() {
        return baseTypes;
    }

    /** Weapon types provided by equipped cursed tools (their moves cost no CE). */
    public Set<WeaponType> cursedToolTypes() {
        return cursedToolTypes;
    }

    /** Ids of the equipped cursed tools, in equipment order. */
    public List<String> cursedToolIds() {
        return cursedToolIds;
    }

    /** True when the character wields the given weapon type from any source. */
    public boolean hasWeaponOfType(WeaponType type) {
        return weaponTypes().contains(type);
    }

    /**
     * True when an equipped cursed tool covers the given weapon move tag —
     * moves of that type cost the wielder no cursed energy.
     */
    public boolean coversWeaponTag(MoveTag tag) {
        return tag != null && cursedToolTypes.contains(WeaponType.fromMoveTag(tag));
    }

    /** Moves bestown by equipped cursed tools. */
    public List<Move> grantedMoves() {
        return grantedMoves;
    }

    /** Ids of the tool-bestown moves. */
    public Set<String> grantedMoveIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Move move : grantedMoves) ids.add(move.getId());
        return ids;
    }

    /** Abilities bestown by equipped cursed tools. */
    public List<Ability> grantedAbilities() {
        return grantedAbilities;
    }

    /** True when there is nothing equipped at all. */
    public boolean isEmpty() {
        return baseTypes.isEmpty() && cursedToolTypes.isEmpty()
            && grantedMoves.isEmpty() && grantedAbilities.isEmpty();
    }
}
