package com.jjktbf.model.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One ordered team of combatants within a {@link BattleState}.
 *
 * <p>Ordering is stable: initial fighters appear first in roster order, then
 * summons in creation order. This stable order is the deterministic fallback
 * for tie-breaking, targeting retargets, and UI page layout (the first/main
 * fighter is always the leftmost page).
 *
 * <p>A team owns its combatants by {@link CombatantId}; defeated and removed
 * combatants remain in the roster (preserving order) but are excluded from the
 * "active"/"living fighter" views.
 */
public final class BattleTeam {

    private final BattleTeamId id;
    private final List<BattleCombatant> combatants = new ArrayList<>();
    private final Map<CombatantId, BattleCombatant> byInstance = new LinkedHashMap<>();

    public BattleTeam(BattleTeamId id) {
        this.id = Objects.requireNonNull(id, "team id");
        if (!BattleTeamId.PLAYER.equals(id) && !BattleTeamId.ENEMY.equals(id)) {
            throw new IllegalArgumentException("Unsupported battle team " + id);
        }
    }

    public BattleTeamId id() {
        return id;
    }

    /** All combatants in stable order, including defeated/removed ones. */
    public List<BattleCombatant> all() {
        return Collections.unmodifiableList(combatants);
    }

    /** Combatants still present in combat (lifecycle ACTIVE or DEFEATED), stable order. */
    public List<BattleCombatant> present() {
        List<BattleCombatant> out = new ArrayList<>();
        for (BattleCombatant c : combatants) {
            if (!c.isRemoved()) out.add(c);
        }
        return out;
    }

    /** Combatants whose lifecycle is ACTIVE (able to act / be targeted), stable order. */
    public List<BattleCombatant> active() {
        List<BattleCombatant> out = new ArrayList<>();
        for (BattleCombatant c : combatants) {
            if (c.isActive()) out.add(c);
        }
        return out;
    }

    /** Living (HP > 0) fighters, stable order. */
    public List<BattleCombatant> livingFighters() {
        List<BattleCombatant> out = new ArrayList<>();
        for (BattleCombatant c : combatants) {
            if (c.isFighter() && !c.isDefeated() && !c.isRemoved()) out.add(c);
        }
        return out;
    }

    /** True once the team has no living fighters left. */
    public boolean isEliminated() {
        return livingFighters().isEmpty();
    }

    public BattleCombatant get(CombatantId instanceId) {
        return instanceId == null ? null : byInstance.get(instanceId);
    }

    public boolean contains(CombatantId instanceId) {
        return instanceId != null && byInstance.containsKey(instanceId);
    }

    /** True only when this exact combatant object belongs to the team. */
    public boolean contains(BattleCombatant combatant) {
        if (combatant == null) return false;
        BattleCombatant registered = byInstance.get(combatant.getInstanceId());
        return registered == combatant;
    }

    public int size() {
        return combatants.size();
    }

    /** Package-private: combatants are registered by {@link BattleState}. */
    void add(BattleCombatant combatant) {
        Objects.requireNonNull(combatant, "combatant");
        if (combatant.getInstanceId() == null) {
            throw new IllegalStateException(
                "Combatant must have an instance id before joining a team");
        }
        if (!id.equals(combatant.getTeamId())) {
            throw new IllegalStateException(
                "Combatant " + combatant.getInstanceId() + " belongs to "
                    + combatant.getTeamId() + ", not " + id);
        }
        if (combatant.getRole() == null) {
            throw new IllegalStateException("Combatant must have a role before joining a team");
        }
        for (BattleCombatant member : combatants) {
            if (member == combatant) {
                throw new IllegalStateException("Combatant object is already on team " + id);
            }
        }
        if (byInstance.containsKey(combatant.getInstanceId())) {
            throw new IllegalStateException(
                "Duplicate combatant instance id " + combatant.getInstanceId());
        }
        combatants.add(combatant);
        byInstance.put(combatant.getInstanceId(), combatant);
    }
}
