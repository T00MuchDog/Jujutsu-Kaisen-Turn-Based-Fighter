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
import java.util.function.Predicate;

/**
 * Everything a character has equipped: base weapons plus cursed tools and the
 * move candidates explicitly assigned to those cursed tools.
 *
 * <ul>
 *   <li>{@link #weaponTypes()} — every weapon type the character wields, from
 *       base weapons and cursed tools alike. Gates weapon-tagged moves.</li>
 *   <li>{@link #cursedToolTypes()} — the subset provided by cursed tools. Moves
 *       of these types cost the wielder no cursed energy and waive jujutsu stat
 *       prerequisites.</li>
 *   <li>{@link #grantedMoves()} — moves derived from equipped tools' exact
 *       assignments. Character construction still applies the ordinary move
 *       requirements before exposing them.</li>
 * </ul>
 *
 * Resolved from {@link CharacterData} by {@link #resolve}; immutable afterwards.
 */
public final class Equipment {

    /** No equipment at all: no weapons, no tools, no granted content. */
    public static final Equipment NONE = new Equipment(
        Set.of(), Set.of(), List.of(), List.of());

    /** Equipment carrying only base weapons (no cursed tools, no granted content). */
    public static Equipment base(WeaponType... types) {
        return new Equipment(Set.of(types), Set.of(), List.of(), List.of());
    }

    /** Equipment carrying a single cursed tool of the given type, granting nothing. */
    public static Equipment cursedTool(WeaponType type) {
        return new Equipment(Set.of(), Set.of(type), List.of(), List.of());
    }

    private final Set<WeaponType> baseTypes;
    private final Set<WeaponType> cursedToolTypes;
    private final List<String> cursedToolIds;
    private final List<Move> grantedMoves;

    private Equipment(
        Set<WeaponType> baseTypes,
        Set<WeaponType> cursedToolTypes,
        List<String> cursedToolIds,
        List<Move> grantedMoves
    ) {
        this.baseTypes        = Set.copyOf(baseTypes);
        this.cursedToolTypes  = Set.copyOf(cursedToolTypes);
        this.cursedToolIds    = List.copyOf(cursedToolIds);
        this.grantedMoves     = List.copyOf(grantedMoves);
    }

    /**
     * Resolve equipment from raw DTO inputs.
     *
     * @param equippedWeaponTypeNames stored {@link WeaponType} names of base
     *                                weapons (may be null/empty)
     * @param equippedCursedToolIds   6-digit {@link CursedToolData} ids; every
     *                                id must resolve in {@code allTools}
     * @param allTools                every known cursed tool definition
     * @param allMoves                all move definitions. An equipped tool
     *                                grants moves explicitly assigned to its ID;
     *                                its weapon type only gates normal learning
     * @throws IllegalArgumentException on unknown weapon type names or tool ids
     */
    public static Equipment resolve(
        List<String> equippedWeaponTypeNames,
        List<String> equippedCursedToolIds,
        List<CursedToolData> allTools,
        List<Move> allMoves
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

        Map<String, Move> grantedById = new LinkedHashMap<>();
        if (allMoves != null) {
            for (Move move : allMoves) {
                if (move == null || move.getId() == null || move.getId().isBlank()) continue;
                boolean assignedToTool = move.getRequiredCursedToolId() != null
                    && toolIds.contains(move.getRequiredCursedToolId());
                if (assignedToTool) {
                    grantedById.putIfAbsent(move.getId(), move);
                }
            }
        }
        return new Equipment(baseTypes, toolTypes, toolIds,
            new ArrayList<>(grantedById.values()));
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

    /** True when at least one move weapon tag is covered by equipped equipment. */
    public boolean supportsWeaponTags(Set<MoveTag> tags) {
        return tags != null && tags.stream()
            .map(WeaponType::fromMoveTag)
            .anyMatch(this::hasWeaponOfType);
    }

    /**
     * True when an equipped cursed tool covers the given weapon move tag —
     * moves of that type cost the wielder no cursed energy.
     */
    public boolean coversWeaponTag(MoveTag tag) {
        return tag != null && cursedToolTypes.contains(WeaponType.fromMoveTag(tag));
    }

    /** True when an equipped cursed tool covers any of the move's weapon tags. */
    public boolean coversWeaponTags(Set<MoveTag> tags) {
        return tags != null && tags.stream().anyMatch(this::coversWeaponTag);
    }

    /** True when this equipment waives a prerequisite for the given weapon move. */
    public boolean waivesJujutsuPrerequisite(Set<MoveTag> tags, StatKey prerequisite) {
        return prerequisite != null && prerequisite.isJujutsuPrerequisite()
            && coversWeaponTags(tags);
    }

    /** Moves bestowed by equipped cursed tools before requirement filtering. */
    public List<Move> grantedMoves() {
        return grantedMoves;
    }

    /** IDs of the tool-bestowed move candidates. */
    public Set<String> grantedMoveIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Move move : grantedMoves) ids.add(move.getId());
        return ids;
    }

    /** Return this equipment with only grant candidates accepted by the predicate. */
    public Equipment filterGrantedMoves(Predicate<Move> predicate) {
        if (predicate == null || grantedMoves.isEmpty()) return this;
        List<Move> filtered = grantedMoves.stream().filter(predicate).toList();
        return filtered.size() == grantedMoves.size() ? this
            : new Equipment(baseTypes, cursedToolTypes, cursedToolIds, filtered);
    }

    /** True when there is nothing equipped at all. */
    public boolean isEmpty() {
        return baseTypes.isEmpty() && cursedToolTypes.isEmpty()
            && grantedMoves.isEmpty();
    }
}
