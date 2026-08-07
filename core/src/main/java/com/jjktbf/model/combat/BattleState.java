package com.jjktbf.model.combat;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Snapshot of the battle's current phase and the full multi-combatant team model.
 *
 * <p>The state machine flows as:
 * <pre>
 *   PLANNING → RESOLUTION → ROUND_END → PLANNING → ...
 *                                  ↓
 *                              BATTLE_OVER
 * </pre>
 *
 * <p>Battles always have exactly two opposing {@link BattleTeam}s, but each team
 * may contain any number of {@link BattleCombatant}s. Combatants are tracked by
 * stable {@link CombatantId}; duplicate live instances of the same character
 * definition are allowed (e.g. two summoned shikigami). A team loses when it has
 * no living {@link CombatantRole#FIGHTER}s; living summons do not prevent defeat.
 *
 * <p>Instance ids are generated deterministically (a monotonic battle-scoped
 * counter seeded by roster order). The legacy {@link #getPlayerCombatant()} /
 * {@link #getEnemyCombatant()} accessors are retained as convenience views over
 * the first fighter of each team so existing 1v1 callers compile, but production
 * combat logic must use the team-aware APIs.
 */
public class BattleState {

    public enum Phase {
        PLANNING,
        RESOLUTION,
        ROUND_END,
        BATTLE_OVER
    }

    private final BattleTeam playerTeam;
    private final BattleTeam enemyTeam;
    private final boolean legacySingleCombatantConstruction;
    private long instanceIdSeq;

    private Phase currentPhase;
    private int   roundNumber;

    /** The AP tick the action counter is currently on during RESOLUTION phase. */
    private int   currentTick;

    /** Set during BATTLE_OVER to the winning team (null = draw / ongoing). */
    private BattleTeamId winnerTeam;
    /** Legacy single-winner view: the first living fighter of the winning team (or null). */
    private BattleCombatant winner;
    private boolean roundEndMaintenanceComplete;
    private final List<AutomaticStatusApplication> pendingAutomaticStatuses = new ArrayList<>();
    /** Pending summons queued during resolution, applied after the current batch. */
    private final List<PendingSummon> pendingSummons = new ArrayList<>();

    public record AutomaticStatusApplication(
        BattleCombatant source,
        BattleCombatant target,
        StatusEffectType status,
        int previousMaxHp,
        int previousMaxCe,
        int resultingMaxHp,
        int resultingMaxCe
    ) { }

    /** A summon request enqueued by a move/ability effect during resolution. */
    public record PendingSummon(
        BattleTeamId teamId,
        BattleCombatant summoner,
        String summonCharacterId
    ) { }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Legacy two-combatant convenience constructor (1v1). Both combatants become
     * the first fighter of their respective team. Kept for existing tests; new
     * code should use {@link #BattleState(BattleTeam, BattleTeam)} or build teams
     * via the add-fighter factory.
     */
    public BattleState(BattleCombatant playerCombatant, BattleCombatant enemyCombatant) {
        this.playerTeam = new BattleTeam(BattleTeamId.PLAYER);
        this.enemyTeam  = new BattleTeam(BattleTeamId.ENEMY);
        this.legacySingleCombatantConstruction = true;
        registerInitialFighter(playerTeam, playerCombatant);
        registerInitialFighter(enemyTeam, enemyCombatant);
        this.currentPhase    = Phase.PLANNING;
        this.roundNumber     = 1;
        this.currentTick     = 0;
        this.winnerTeam      = null;
        this.winner          = null;
        this.roundEndMaintenanceComplete = false;
        applyAutomaticStatuses(AbilityEffectTiming.FIGHT_START);
        applyAutomaticStatuses(AbilityEffectTiming.ROUND_START);
    }

    /**
     * Two-team constructor for multi-combatant battles. Each team must already
     * contain its initial fighters in roster order. The first argument must be
     * PLAYER and the second must be ENEMY.
     */
    public BattleState(BattleTeam playerTeam, BattleTeam enemyTeam) {
        Objects.requireNonNull(playerTeam, "playerTeam");
        Objects.requireNonNull(enemyTeam, "enemyTeam");
        if (!BattleTeamId.PLAYER.equals(playerTeam.id())) {
            throw new IllegalArgumentException("playerTeam must have id PLAYER");
        }
        if (!BattleTeamId.ENEMY.equals(enemyTeam.id())) {
            throw new IllegalArgumentException("enemyTeam must have id ENEMY");
        }
        validateInitialTeams(playerTeam, enemyTeam);
        this.playerTeam = playerTeam;
        this.enemyTeam  = enemyTeam;
        this.legacySingleCombatantConstruction = false;
        seedInstanceIdSequence();
        this.currentPhase    = Phase.PLANNING;
        this.roundNumber     = 1;
        this.currentTick     = 0;
        this.winnerTeam      = null;
        this.winner          = null;
        this.roundEndMaintenanceComplete = false;
        applyAutomaticStatuses(AbilityEffectTiming.FIGHT_START);
        applyAutomaticStatuses(AbilityEffectTiming.ROUND_START);
    }

    /** Register a fighter as the first combatant of its team and assign identity. */
    private void registerInitialFighter(BattleTeam team, BattleCombatant combatant) {
        Objects.requireNonNull(combatant, "combatant");
        CombatantId instanceId = nextInstanceId(team.id());
        combatant.assignIdentity(instanceId, team.id(), team.size(),
            CombatantRole.FIGHTER, null);
        team.add(combatant);
    }

    private CombatantId nextInstanceId(BattleTeamId teamId) {
        while (instanceIdSeq < Long.MAX_VALUE) {
            CombatantId candidate = new CombatantId(
                teamId.value() + "-" + (++instanceIdSeq));
            if (combatant(candidate) == null) return candidate;
        }
        throw new IllegalStateException("Combatant instance id space is exhausted");
    }

    private static void validateInitialTeams(BattleTeam playerTeam, BattleTeam enemyTeam) {
        Set<BattleCombatant> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<CombatantId> ids = new HashSet<>();
        for (BattleTeam team : List.of(playerTeam, enemyTeam)) {
            for (int index = 0; index < team.all().size(); index++) {
                BattleCombatant combatant = team.all().get(index);
                if (!objects.add(combatant)) {
                    throw new IllegalArgumentException(
                        "The same combatant object cannot belong to multiple roster slots");
                }
                if (combatant.getInstanceId() == null || !ids.add(combatant.getInstanceId())) {
                    throw new IllegalArgumentException("Combatant instance ids must be unique");
                }
                if (!team.id().equals(combatant.getTeamId()) || !team.contains(combatant)) {
                    throw new IllegalArgumentException(
                        "Combatant identity does not match its team membership");
                }
                if (combatant.getRole() == null || combatant.getRosterOrder() != index) {
                    throw new IllegalArgumentException(
                        "Combatant role/order does not match its roster membership");
                }
            }
        }
    }

    /** Seed only from ids generated by this state's exact {@code TEAM-number} format. */
    private void seedInstanceIdSequence() {
        long maximum = 0;
        for (BattleCombatant combatant : allCombatants()) {
            String value = combatant.getInstanceId().value();
            String prefix = combatant.getTeamId().value() + "-";
            if (!value.startsWith(prefix)) continue;
            String suffix = value.substring(prefix.length());
            if (suffix.isEmpty() || !suffix.chars().allMatch(java.lang.Character::isDigit)) {
                continue;
            }
            try {
                maximum = Math.max(maximum, Long.parseLong(suffix));
            } catch (NumberFormatException ignored) {
                // An oversized external id remains reserved in the team map; generated
                // ids still use the next representable deterministic sequence value.
            }
        }
        instanceIdSeq = maximum;
    }

    /**
     * Build a fresh {@link BattleTeam} with the given fighters assigned stable
     * instance ids in roster order. Used to programmatically construct 2v2 (or
     * larger) teams for tests and future match setup.
     */
    public static BattleTeam teamOfFighters(BattleTeamId teamId, List<BattleCombatant> fighters) {
        BattleTeam team = new BattleTeam(teamId);
        int order = 0;
        for (BattleCombatant fighter : fighters) {
            CombatantId instanceId = new CombatantId(teamId.value() + "-f" + (order + 1));
            fighter.assignIdentity(instanceId, teamId, order, CombatantRole.FIGHTER, null);
            team.add(fighter);
            order++;
        }
        return team;
    }

    /**
     * Add an additional fighter to an existing team mid-construction (before the
     * battle starts). Assigns a fresh instance id and stable roster order.
     */
    public BattleCombatant addFighter(BattleTeamId teamId, BattleCombatant fighter) {
        BattleTeam team = teamOf(teamId);
        if (team == null) {
            throw new IllegalArgumentException("Unknown team " + teamId);
        }
        CombatantId instanceId = nextInstanceId(teamId);
        fighter.assignIdentity(instanceId, teamId, team.size(), CombatantRole.FIGHTER, null);
        team.add(fighter);
        return fighter;
    }

    // -------------------------------------------------------------------------
    // Phase transitions
    // -------------------------------------------------------------------------

    public void transitionTo(Phase phase) {
        this.currentPhase = phase;
        if (phase == Phase.ROUND_END) roundEndMaintenanceComplete = false;
        if (phase == Phase.RESOLUTION) {
            this.currentTick = 1;
        }
    }

    public void advanceTick() {
        currentTick++;
    }

    public void endRound() {
        roundNumber++;
        currentTick = 0;
        applyAutomaticStatuses(AbilityEffectTiming.ROUND_START);
    }

    // -------------------------------------------------------------------------
    // Automatic statuses (generalized over all active combatants)
    // -------------------------------------------------------------------------

    private void applyAutomaticStatuses(AbilityEffectTiming timing) {
        for (BattleCombatant owner : activeCombatants()) {
            applyAutomaticStatusesFrom(owner, timing);
        }
    }

    /**
     * Apply an owner's AUTO_STATUS_APPLY effects. {@code SELF} targets the owner;
     * {@code ENEMY}/{@code BOTH} fan out to every active enemy (multi-combatant).
     */
    private void applyAutomaticStatusesFrom(
        BattleCombatant owner,
        AbilityEffectTiming timing
    ) {
        for (AbilityEffectData effect : owner.getAbilityFlags().autoStatusEffects) {
            if (!timing.name().equals(effect.timing)) continue;
            boolean targetsEnemy = AbilityEffectTarget.ENEMY.name().equals(effect.target)
                || AbilityEffectTarget.BOTH.name().equals(effect.target);
            boolean targetsSelf = !AbilityEffectTarget.ENEMY.name().equals(effect.target);
            if (targetsSelf) {
                recordAutomaticStatus(owner, owner, effect);
            }
            if (targetsEnemy) {
                for (BattleCombatant enemy : activeEnemiesOf(owner)) {
                    recordAutomaticStatus(owner, enemy, effect);
                }
            }
        }
    }

    private void recordAutomaticStatus(
        BattleCombatant source,
        BattleCombatant target,
        AbilityEffectData effect
    ) {
        int previousMaxHp = target.getMaxHp();
        int previousMaxCe = target.getMaxCursedEnergy();
        if (!target.addAutomaticStatusEffect(effect)) return;
        try {
            int resultingMaxHp = target.getMaxHp();
            int resultingMaxCe = target.getMaxCursedEnergy();
            if (target.isPoolClampDeferred()) {
                previousMaxHp = resultingMaxHp;
                previousMaxCe = resultingMaxCe;
            }
            pendingAutomaticStatuses.add(new AutomaticStatusApplication(
                source, target, StatusEffectType.fromName(
                    effect.stringValue, effect.magnitude != null ? effect.magnitude : 0.0),
                previousMaxHp, previousMaxCe,
                resultingMaxHp, resultingMaxCe));
        } catch (IllegalArgumentException ignored) { }
    }

    public List<AutomaticStatusApplication> drainAutomaticStatusApplications() {
        if (pendingAutomaticStatuses.isEmpty()) return List.of();
        List<AutomaticStatusApplication> drained = List.copyOf(pendingAutomaticStatuses);
        pendingAutomaticStatuses.clear();
        return drained;
    }

    // -------------------------------------------------------------------------
    // Team / combatant queries
    // -------------------------------------------------------------------------

    public BattleTeam playerTeam() { return playerTeam; }
    public BattleTeam enemyTeam()  { return enemyTeam; }

    public BattleTeam teamOf(BattleCombatant combatant) {
        if (combatant == null) return null;
        if (playerTeam.contains(combatant)) return playerTeam;
        if (enemyTeam.contains(combatant)) return enemyTeam;
        return null;
    }

    public BattleTeam oppositeTeamOf(BattleCombatant combatant) {
        BattleTeam ownTeam = teamOf(combatant);
        if (ownTeam == null) return null;
        return ownTeam == playerTeam ? enemyTeam : playerTeam;
    }

    public BattleTeam teamOf(BattleTeamId teamId) {
        if (BattleTeamId.PLAYER.equals(teamId)) return playerTeam;
        if (BattleTeamId.ENEMY.equals(teamId)) return enemyTeam;
        return null;
    }

    /** All combatants on both teams (including defeated/removed), stable order. */
    public List<BattleCombatant> allCombatants() {
        List<BattleCombatant> out = new ArrayList<>(playerTeam.size() + enemyTeam.size());
        out.addAll(playerTeam.all());
        out.addAll(enemyTeam.all());
        return out;
    }

    /** All present (non-removed) combatants on both teams, stable order. */
    public List<BattleCombatant> presentCombatants() {
        List<BattleCombatant> out = new ArrayList<>();
        out.addAll(playerTeam.present());
        out.addAll(enemyTeam.present());
        return out;
    }

    /** All ACTIVE combatants on both teams, stable order (player team first). */
    public List<BattleCombatant> activeCombatants() {
        List<BattleCombatant> out = new ArrayList<>();
        out.addAll(playerTeam.active());
        out.addAll(enemyTeam.active());
        return out;
    }

    /** Active allies of the given combatant (same team, ACTIVE), excluding self. */
    public List<BattleCombatant> activeAlliesOf(BattleCombatant combatant) {
        BattleTeam team = teamOf(combatant);
        if (team == null) return List.of();
        List<BattleCombatant> out = new ArrayList<>();
        for (BattleCombatant c : team.active()) {
            if (c != combatant && c.isActive()) out.add(c);
        }
        return out;
    }

    /** Active enemies of the given combatant (opposing team, ACTIVE), stable order. */
    public List<BattleCombatant> activeEnemiesOf(BattleCombatant combatant) {
        BattleTeam enemy = oppositeTeamOf(combatant);
        if (enemy == null) return List.of();
        return enemy.active();
    }

    /** Resolve a combatant by instance id across both teams. */
    public BattleCombatant combatant(CombatantId instanceId) {
        BattleCombatant c = playerTeam.get(instanceId);
        return c != null ? c : enemyTeam.get(instanceId);
    }

    public Optional<BattleCombatant> findCombatant(CombatantId instanceId) {
        return Optional.ofNullable(combatant(instanceId));
    }

    /**
     * First living enemy in stable roster order. Used as the deterministic
     * retarget fallback when a single-target move's selected target is invalid.
     */
    public BattleCombatant firstActiveEnemyOf(BattleCombatant combatant) {
        List<BattleCombatant> enemies = activeEnemiesOf(combatant);
        return enemies.isEmpty() ? null : enemies.get(0);
    }

    // -------------------------------------------------------------------------
    // Summon creation and recursive dismissal
    // -------------------------------------------------------------------------

    /**
     * Enqueue a summon to be created for {@code summoner}'s team. The actual
     * combatant is materialized via {@link #drainPendingSummons(BattleCharacterLookup)}
     * so summons created mid-resolution batch do not retroactively join the
     * current firing list / AOE snapshot.
     */
    public void enqueueSummon(BattleCombatant summoner, String summonCharacterId) {
        BattleTeam team = teamOf(summoner);
        if (team == null || !summoner.isActive() || summoner.isDefeated()
            || summonCharacterId == null || summonCharacterId.isBlank()) {
            return;
        }
        pendingSummons.add(new PendingSummon(team.id(), summoner, summonCharacterId));
    }

    /**
     * Materialize all pending summons against the given character lookup. Each
     * summon begins at full HP/CE, is a {@link CombatantRole#SUMMON} owned by its
     * summoner, and joins planning next round. Returns the created combatants.
     */
    public List<BattleCombatant> drainPendingSummons(BattleCharacterLookup lookup) {
        if (pendingSummons.isEmpty()) return List.of();
        List<PendingSummon> drained = List.copyOf(pendingSummons);
        pendingSummons.clear();
        List<BattleCombatant> created = new ArrayList<>();
        for (PendingSummon pending : drained) {
            BattleTeam summonerTeam = teamOf(pending.summoner());
            if (!pending.summoner().isActive() || pending.summoner().isDefeated()
                || summonerTeam == null
                || !summonerTeam.id().equals(pending.teamId())) {
                continue;
            }
            Optional<Character> definition = lookup == null
                ? Optional.empty() : lookup.findCharacter(pending.summonCharacterId());
            if (definition.isEmpty()) {
                System.err.println("[WARN] Unknown summon character id "
                    + pending.summonCharacterId() + " — summon skipped");
                continue;
            }
            if (definition.get().getType() != CharacterType.SHIKIGAMI) {
                System.err.println("[WARN] Summon character id "
                    + pending.summonCharacterId() + " is not a SHIKIGAMI — summon skipped");
                continue;
            }
            BattleTeam team = summonerTeam;
            BattleCombatant summon = new BattleCombatant(definition.get(), definition.get().getAbilities());
            CombatantId instanceId = nextInstanceId(team.id());
            summon.assignIdentity(instanceId, team.id(), team.size(),
                CombatantRole.SUMMON, pending.summoner().getInstanceId());
            team.add(summon);
            created.add(summon);
        }
        return created;
    }

    /** Pending (not yet materialized) summons queued during the current resolution. */
    public List<PendingSummon> pendingSummons() {
        return List.copyOf(pendingSummons);
    }

    /**
     * Recursively dismiss (mark REMOVED) every summon owned by {@code summoner},
     * including summons owned by those summons. Already-launched delayed hit
     * components are intentionally left in flight (their targets may now be gone,
     * which the resolver handles by producing no hit).
     */
    public int recursivelyDismissSummonsOf(BattleCombatant summoner) {
        List<BattleCombatant> dismissed = dismissSummonsOf(summoner);
        cancelPendingSummonsForInactiveSummoners();
        return dismissed.size();
    }

    private List<BattleCombatant> dismissSummonsOf(BattleCombatant summoner) {
        if (summoner == null || summoner.getInstanceId() == null) return List.of();
        List<BattleCombatant> dismissed = new ArrayList<>();
        Set<BattleCombatant> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<BattleCombatant> frontier = new ArrayList<>(List.of(summoner));
        while (!frontier.isEmpty()) {
            List<BattleCombatant> nextFrontier = new ArrayList<>();
            for (BattleCombatant owner : frontier) {
                for (BattleCombatant candidate : allCombatants()) {
                    if (owner.getInstanceId().equals(candidate.getSummonerId())
                        && visited.add(candidate)) {
                        nextFrontier.add(candidate);
                        if (!candidate.isRemoved()) {
                            candidate.markRemoved();
                            dismissed.add(candidate);
                        }
                    }
                }
            }
            frontier = nextFrontier;
        }
        return dismissed;
    }

    // -------------------------------------------------------------------------
    // Lifecycle reconciliation
    // -------------------------------------------------------------------------

    /**
     * Reconcile individual combatant defeat/removal against team victory. Marks
     * defeated fighters, recursively dismisses a summoner's summons when it is
     * defeated, and removes defeated summons. Does NOT end the battle — callers
     * check {@link #checkAndResolveBattleOver()} after this.
     *
     * @return the combatants whose lifecycle changed this pass (for event emission)
     */
    public List<BattleCombatant> reconcileDefeats() {
        List<BattleCombatant> changed = new ArrayList<>();
        Set<BattleCombatant> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        List<BattleCombatant> newlyDefeated = new ArrayList<>();
        for (BattleCombatant c : presentCombatants()) {
            if (!c.isActive()) continue;
            if (c.isDefeated()) {
                c.markLifecycleDefeated();
                changed.add(c);
                recorded.add(c);
                newlyDefeated.add(c);
            }
        }
        // Every defeated owner, including a summon, dismisses its full descendant tree.
        for (BattleCombatant defeated : newlyDefeated) {
            for (BattleCombatant dismissed : dismissSummonsOf(defeated)) {
                if (recorded.add(dismissed)) changed.add(dismissed);
            }
        }
        // Defeated summons are removed from active combat.
        for (BattleCombatant c : allCombatants()) {
            if (c.isLifecycleDefeated() && c.isSummon() && !c.isRemoved()) {
                c.markRemoved();
                if (recorded.add(c)) changed.add(c);
            }
        }
        cancelPendingSummonsForInactiveSummoners();
        return changed;
    }

    private void cancelPendingSummonsForInactiveSummoners() {
        pendingSummons.removeIf(pending -> !pending.summoner().isActive()
            || pending.summoner().isDefeated()
            || teamOf(pending.summoner()) == null
            || !pending.teamId().equals(pending.summoner().getTeamId()));
    }

    // -------------------------------------------------------------------------
    // Win condition check
    // -------------------------------------------------------------------------

    /**
     * Check if the battle is over. A team loses when it has no living fighters.
     * Sets the winning team and transitions to BATTLE_OVER.
     * @return true if the battle ended
     */
    public boolean checkAndResolveBattleOver() {
        boolean playerEliminated = playerTeam.isEliminated();
        boolean enemyEliminated  = enemyTeam.isEliminated();

        if (playerEliminated || enemyEliminated) {
            currentPhase = Phase.BATTLE_OVER;
            if (!playerEliminated && enemyEliminated) {
                winnerTeam = playerTeam.id();
                winner = firstLivingFighter(playerTeam);
            } else if (playerEliminated && !enemyEliminated) {
                winnerTeam = enemyTeam.id();
                winner = firstLivingFighter(enemyTeam);
            } else {
                winnerTeam = null; // simultaneous wipe — draw
                winner = null;
            }
            return true;
        }
        return false;
    }

    private BattleCombatant firstLivingFighter(BattleTeam team) {
        List<BattleCombatant> living = team.livingFighters();
        return living.isEmpty() ? null : living.get(0);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Legacy 1v1 accessor: the first combatant of the player team. New code must
     * use the team-aware APIs; this exists so existing 1v1 callers compile.
     */
    public BattleCombatant getPlayerCombatant() {
        return playerTeam.all().isEmpty() ? null : playerTeam.all().get(0);
    }

    /**
     * Legacy 1v1 accessor: the first combatant of the enemy team. New code must
     * use the team-aware APIs; this exists so existing 1v1 callers compile.
     */
    public BattleCombatant getEnemyCombatant() {
        return enemyTeam.all().isEmpty() ? null : enemyTeam.all().get(0);
    }

    public Phase           getCurrentPhase()    { return currentPhase; }
    public int             getRoundNumber()     { return roundNumber; }
    public int             getCurrentTick()     { return currentTick; }

    /** The winning team, or null on a draw / while ongoing. */
    public BattleTeamId    getWinnerTeam()      { return winnerTeam; }

    /** Legacy single-winner view (first living fighter of the winning team). */
    public BattleCombatant getWinner()          { return winner; }

    public boolean         isBattleOver()       { return currentPhase == Phase.BATTLE_OVER; }
    public boolean         isRoundEndMaintenanceComplete() { return roundEndMaintenanceComplete; }
    public void            markRoundEndMaintenanceComplete() { roundEndMaintenanceComplete = true; }

    /** Legacy 1v1 integrations retain their pre-team combat-event surface. */
    boolean usesLegacySingleCombatantConstruction() {
        return legacySingleCombatantConstruction;
    }

    @Override
    public String toString() {
        return String.format("BattleState{Round=%d Phase=%s Tick=%d | %s vs %s}",
            roundNumber, currentPhase, currentTick, playerTeam.id(), enemyTeam.id());
    }
}
