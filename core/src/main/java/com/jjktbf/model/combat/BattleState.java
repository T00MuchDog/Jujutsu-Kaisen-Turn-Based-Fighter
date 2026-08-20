package com.jjktbf.model.combat;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private int   timelineGridLength;
    private int   finalizedTimelineGridRound;

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
    private final Map<CombatantId, Set<String>> destroyedSummonsByOwner = new LinkedHashMap<>();
    private final Map<CombatantId, Map<String, Integer>> summonCooldownsByOwner = new LinkedHashMap<>();
    private final List<BattleCombatant> pendingLifecycleChanges = new ArrayList<>();

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
        String summonCharacterId,
        boolean innateTechniqueBased
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
        recomputeTimelineGridLength();
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
        recomputeTimelineGridLength();
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
        refreshUnfinalizedTimelineGridLength();
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
        recomputeTimelineGridLength();
    }

    /** Fix this round's shared timeline size after all round-start effects run. */
    void finalizeTimelineGridLengthForRound() {
        if (finalizedTimelineGridRound == roundNumber) return;
        recomputeTimelineGridLength();
        finalizedTimelineGridRound = roundNumber;
    }

    private void refreshUnfinalizedTimelineGridLength() {
        if (finalizedTimelineGridRound != roundNumber) recomputeTimelineGridLength();
    }

    private void recomputeTimelineGridLength() {
        int strongestAp = 0;
        for (BattleCombatant combatant : activeCombatants()) {
            strongestAp = Math.max(strongestAp, combatant.getMaxApBar());
        }
        timelineGridLength = Timeline.gridLengthForStrongestAp(strongestAp);
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

    /**
     * The active enemy of {@code combatant} currently holding the strongest
     * active Taunt, or {@code null} if none of its enemies are taunting. Stable
     * roster order is the tie-break between equal-strength taunts. Used to
     * redirect single-target MELEE attacks onto the taunter at fire time.
     */
    public BattleCombatant taunterOf(BattleCombatant combatant) {
        BattleCombatant strongest = null;
        int strongestTicks = 0;
        for (BattleCombatant enemy : activeEnemiesOf(combatant)) {
            int ticks = enemy.getActiveTauntRemainingTicks();
            if (ticks > strongestTicks) {
                strongestTicks = ticks;
                strongest = enemy;
            }
        }
        return strongest;
    }

    // -------------------------------------------------------------------------
    // Summon creation and recursive dismissal
    // -------------------------------------------------------------------------

    /**
     * Enqueue a summon to be created for {@code summoner}'s team. The actual
     * combatant is materialized via {@link #drainPendingSummons(BattleCharacterLookup)}
     * at the summon's broadcast, so it is active for every later move the same
     * tick (AOE snapshots include it; the shikigami-locked move gate sees it).
     * It receives no timeline until the next planning phase.
     *
     * @param innateTechniqueBased  {@code true} if the summoning move is innate-technique
     *      based (the shikigami scales with the summoner's CTM); {@code false} for a
     *      non-innate summon (scales with the summoner's Jujutsu Skill). See {@link SummonStatScaler}.
     */
    public boolean enqueueSummon(
        BattleCombatant summoner, String summonCharacterId, boolean innateTechniqueBased
    ) {
        BattleTeam team = teamOf(summoner);
        if (team == null || !summoner.isActive() || summoner.isDefeated()
            || summonCharacterId == null || summonCharacterId.isBlank()) {
            return false;
        }
        String definitionId = summonCharacterId.trim();
        if (summonRestrictionReason(summoner, definitionId) != null) return false;
        pendingSummons.add(new PendingSummon(team.id(), summoner, definitionId, innateTechniqueBased));
        return true;
    }

    /**
     * Enqueue an innate-technique-based summon (the common case — e.g. all Ten Shadows
     * shikigami). Equivalent to {@code enqueueSummon(summoner, id, true)}. Kept for
     * callers without a move context (tests, direct enqueue).
     */
    public boolean enqueueSummon(BattleCombatant summoner, String summonCharacterId) {
        return enqueueSummon(summoner, summonCharacterId, true);
    }

    /** Human-readable reason this definition cannot currently be summoned, or null. */
    public String summonRestrictionReason(BattleCombatant summoner, String summonCharacterId) {
        if (summoner == null || summonCharacterId == null || summonCharacterId.isBlank()) {
            return "A valid summoner and shikigami definition are required.";
        }
        String definitionId = summonCharacterId.trim();
        if (isSummonDestroyed(summoner, definitionId)) {
            return "This shikigami was destroyed and cannot be summoned again this battle.";
        }
        Integer availableRound = summonAvailableRound(summoner, definitionId);
        if (availableRound != null && roundNumber < availableRound) {
            return "This shikigami can be summoned again in round " + availableRound + ".";
        }
        Integer cap = summoner.getAbilityFlags().maxActiveSummons;
        if (cap == null) return null;
        if (hasDirectActiveSummonDefinition(summoner, definitionId)
            || hasPendingSummonDefinition(summoner, definitionId)) {
            return "This shikigami is already active or pending.";
        }
        if (directActiveSummonCount(summoner) + directPendingSummonCount(summoner) >= cap) {
            return "Maximum active summons reached.";
        }
        return null;
    }

    public boolean isSummonDestroyed(BattleCombatant summoner, String summonCharacterId) {
        return summoner != null && summoner.getInstanceId() != null
            && summonCharacterId != null
            && destroyedSummonsByOwner.getOrDefault(summoner.getInstanceId(), Set.of())
                .contains(summonCharacterId.trim());
    }

    public Set<String> destroyedSummonDefinitionIds(BattleCombatant summoner) {
        if (summoner == null || summoner.getInstanceId() == null) return Set.of();
        return Set.copyOf(destroyedSummonsByOwner.getOrDefault(
            summoner.getInstanceId(), Set.of()));
    }

    /**
     * Mark a shikigami definition as permanently destroyed for this summoner for
     * the remainder of the battle, so it cannot be resummoned and its summon move
     * stays greyed out. Recorded by the summoning technique's coded runtime (the
     * Ten Shadows technique) when one of its owned shikigami is destroyed — not
     * keyed on the unrelated {@code maxActiveSummons} cap.
     */
    public void recordSummonDestroyed(BattleCombatant summoner, String summonCharacterId) {
        if (summoner == null || summoner.getInstanceId() == null
            || summonCharacterId == null || summonCharacterId.isBlank()) return;
        destroyedSummonsByOwner
            .computeIfAbsent(summoner.getInstanceId(), ignored -> new HashSet<>())
            .add(summonCharacterId.trim());
    }

    public boolean isSummonOnCooldown(BattleCombatant summoner, String summonCharacterId) {
        Integer availableRound = summonAvailableRound(summoner, summonCharacterId);
        return availableRound != null && roundNumber < availableRound;
    }

    public Integer summonAvailableRound(BattleCombatant summoner, String summonCharacterId) {
        if (summoner == null || summoner.getInstanceId() == null
            || summonCharacterId == null) return null;
        return summonCooldownsByOwner
            .getOrDefault(summoner.getInstanceId(), Map.of())
            .get(summonCharacterId.trim());
    }

    public Set<String> voluntarilyDesummonedDefinitionIds(BattleCombatant summoner) {
        if (summoner == null || summoner.getInstanceId() == null) return Set.of();
        Map<String, Integer> cooldowns = summonCooldownsByOwner.getOrDefault(
            summoner.getInstanceId(), Map.of());
        Set<String> active = new HashSet<>();
        cooldowns.forEach((id, availableRound) -> {
            if (roundNumber < availableRound) active.add(id);
        });
        return Set.copyOf(active);
    }

    public boolean hasDirectActiveSummonDefinition(
        BattleCombatant summoner, String definitionId
    ) {
        if (summoner == null || summoner.getInstanceId() == null
            || definitionId == null || definitionId.isBlank()) return false;
        String normalizedDefinitionId = definitionId.trim();
        return allCombatants().stream().anyMatch(candidate -> candidate.isActive()
            && candidate.isSummon()
            && summoner.getInstanceId().equals(candidate.getSummonerId())
            && normalizedDefinitionId.equals(candidate.getOriginCharacter().getId()));
    }

    private boolean hasPendingSummonDefinition(BattleCombatant summoner, String definitionId) {
        return pendingSummons.stream().anyMatch(pending -> pending.summoner() == summoner
            && definitionId.equals(pending.summonCharacterId()));
    }

    public int directActiveSummonCount(BattleCombatant summoner) {
        if (summoner == null || summoner.getInstanceId() == null) return 0;
        return (int) allCombatants().stream().filter(candidate -> candidate.isActive()
            && summoner.getInstanceId().equals(candidate.getSummonerId())).count();
    }

    public int directPendingSummonCount(BattleCombatant summoner) {
        if (summoner == null) return 0;
        return (int) pendingSummons.stream()
            .filter(pending -> pending.summoner() == summoner).count();
    }

    /**
     * Materialize all pending summons against the given character lookup. Each
     * summon begins at full HP/CE, is a {@link CombatantRole#SUMMON} owned by its
     * summoner, and joins planning next round (timelines are only attached
     * during planning). Called the moment a summon is enqueued so the new
     * combatant takes effect on the tick it was summoned. Returns the created
     * combatants.
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
            // Scale the shikigami's raw base stats to its summoner's governing
            // stats (CTM:CEO for innate summons, JS:CEO otherwise). The scaled
            // stats feed the ability pipeline; the shikigami's own abilities
            // still apply on top of them.
            CharacterStats scaledBase = SummonStatScaler.scale(
                pending.summoner().getEffectiveStats(),
                definition.get().getBaseStats(),
                pending.innateTechniqueBased(),
                pending.summoner().getStatMode());
            BattleCombatant summon = new BattleCombatant(
                definition.get(), definition.get().getAbilities(), scaledBase,
                pending.summoner().getStatMode());
            CombatantId instanceId = nextInstanceId(team.id());
            summon.assignIdentity(instanceId, team.id(), team.size(),
                CombatantRole.SUMMON, pending.summoner().getInstanceId());
            team.add(summon);
            created.add(summon);
        }
        refreshUnfinalizedTimelineGridLength();
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

    /** Voluntarily dismiss this summon and its descendants until the next round. */
    public int voluntarilyDesummon(BattleCombatant summon) {
        if (summon == null || !summon.isActive() || !summon.isSummon()) return 0;
        return voluntarilyRemoveTrees(List.of(summon));
    }

    /** Voluntarily dismiss all direct summons and their descendant trees. */
    public int voluntarilyDesummonOwnedShikigami(BattleCombatant summoner) {
        if (summoner == null || summoner.getInstanceId() == null) return 0;
        List<BattleCombatant> roots = allCombatants().stream()
            .filter(candidate -> candidate.isActive()
                && summoner.getInstanceId().equals(candidate.getSummonerId()))
            .toList();
        for (PendingSummon pending : List.copyOf(pendingSummons)) {
            if (pending.summoner() != summoner) continue;
            recordSummonCooldown(summoner, pending.summonCharacterId());
            pendingSummons.remove(pending);
        }
        return voluntarilyRemoveTrees(roots);
    }

    private int voluntarilyRemoveTrees(List<BattleCombatant> roots) {
        Set<BattleCombatant> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<BattleCombatant> toRemove = new ArrayList<>();
        List<BattleCombatant> frontier = new ArrayList<>(roots);
        while (!frontier.isEmpty()) {
            BattleCombatant current = frontier.remove(0);
            if (!current.isActive() || !visited.add(current)) continue;
            toRemove.add(current);
            if (current.getSummonerId() != null) {
                BattleCombatant owner = combatant(current.getSummonerId());
                recordSummonCooldown(owner, current.getOriginCharacter().getId());
            }
            for (BattleCombatant candidate : allCombatants()) {
                if (current.getInstanceId().equals(candidate.getSummonerId())) {
                    frontier.add(candidate);
                }
            }
        }
        for (BattleCombatant removed : toRemove) {
            removed.markRemoved();
            pendingLifecycleChanges.add(removed);
        }
        cancelPendingSummonsForInactiveSummoners();
        return toRemove.size();
    }

    private void recordSummonCooldown(BattleCombatant owner, String definitionId) {
        if (owner == null || owner.getInstanceId() == null
            || definitionId == null || definitionId.isBlank()) return;
        summonCooldownsByOwner
            .computeIfAbsent(owner.getInstanceId(), ignored -> new LinkedHashMap<>())
            .put(definitionId.trim(), roundNumber + 1);
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
        List<BattleCombatant> changed = new ArrayList<>(pendingLifecycleChanges);
        pendingLifecycleChanges.clear();
        Set<BattleCombatant> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        recorded.addAll(changed);
        List<BattleCombatant> newlyDefeated = new ArrayList<>();
        for (BattleCombatant c : presentCombatants()) {
            if (!c.isActive()) continue;
            if (c.isDefeated()) {
                if (c.isSummon() && c.getSummonerId() != null) {
                    BattleCombatant owner = combatant(c.getSummonerId());
                    // Permanence of a destroyed shikigami is a property of the
                    // summoning technique (e.g. the Ten Shadows technique), not
                    // of the unrelated maxActiveSummons cap, so the technique's
                    // coded runtime owns the recording via the lifecycle hook.
                    if (owner != null) {
                        owner.getCodedAbilities()
                            .onOwnedSummonDestroyed(this, owner, c);
                    }
                }
                c.markLifecycleDefeated();
                if (recorded.add(c)) changed.add(c);
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
    public int             getTimelineGridLength() { return timelineGridLength; }

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
