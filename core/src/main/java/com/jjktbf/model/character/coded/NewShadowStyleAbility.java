package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;

import java.util.List;
import java.util.Set;

/** Runtime for New Shadow Style's Simple Domain stance and Miwa's binding vow. */
public final class NewShadowStyleAbility implements CodedAbilityRuntime {

    public static final String KEY = "NEW_SHADOW_STYLE";
    public static final String ACTIVATE_SIMPLE_DOMAIN = "ACTIVATE_SIMPLE_DOMAIN";
    public static final String SIMPLE_DOMAIN_BINDING_VOW = "SIMPLE_DOMAIN_BINDING_VOW";

    private final BattleCombatant owner;
    private final Set<String> features;
    private boolean simpleDomainActive;
    private final String simpleDomainMoveId;
    private final Move reactionMove;

    NewShadowStyleAbility(BattleCombatant owner, Set<String> features) {
        this.owner = owner;
        this.features = Set.copyOf(features);
        Move simpleDomain = owner.getCharacter().getKnownMoves().stream()
            .filter(NewShadowStyleAbility::activatesSimpleDomain)
            .findFirst().orElseThrow(() -> new IllegalStateException(
                "New Shadow Style runtime requires Simple Domain"));
        this.simpleDomainMoveId = simpleDomain.getId();
        String reactionMoveId = simpleDomain.getSelfEffects().stream()
            .filter(NewShadowStyleAbility::isActivation)
            .map(StatusEffect::getCodedTarget)
            .findFirst().orElse(null);
        this.reactionMove = owner.getCharacter().getKnownMoves().stream()
            .filter(move -> move.getId().equals(reactionMoveId))
            .filter(NewShadowStyleAbility::isValidReactionMove)
            .findFirst().orElseThrow(() -> new IllegalStateException(
                "Simple Domain requires its linked reinforced Batto reaction move"));
    }

    @Override
    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
        if (!simpleDomainActive || !features.contains(SIMPLE_DOMAIN_BINDING_VOW)
            || trigger.type() != AbilityTrigger.Type.MOVE_USED || trigger.actor() != owner
            || trigger.move() == null || trigger.move().getId().equals(simpleDomainMoveId)) {
            return List.of();
        }
        simpleDomainActive = false;
        return List.of(event(trigger.tick(), "Using " + trigger.move().getName()
            + " dispels " + owner.getCharacter().getName() + "'s Simple Domain."));
    }

    @Override
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        if (attacker != owner || !isActivation(effect)) return List.of();
        simpleDomainActive = true;
        return List.of(event(tick, owner.getCharacter().getName()
            + " establishes a 2.21 metre Simple Domain."));
    }

    @Override
    public CodedMoveResponse beforeIncomingMove(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick
    ) {
        if (!simpleDomainActive || defender != owner || !move.hasTag(MoveTag.ATTACK.name())
            || (!move.isMelee() && !move.isRanged())) {
            return CodedMoveResponse.none();
        }

        simpleDomainActive = false;
        boolean fullBlock = move.getTags().contains(MoveTag.RANGED)
            && move.getTags().contains(MoveTag.PHYSICAL);
        List<Move> reactions = move.isMelee() && reactionMove != null
            ? List.of(reactionMove) : List.of();
        return new CodedMoveResponse(fullBlock, reactions, List.of(event(tick,
            owner.getCharacter().getName() + "'s Simple Domain intercepts "
                + move.getName() + " and is dispelled.")));
    }

    @Override
    public boolean preventFatalDamage() {
        return false;
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Simple Domain", simpleDomainActive ? 1 : 0, 1);
    }

    public static boolean supportsFeature(String feature) {
        return SIMPLE_DOMAIN_BINDING_VOW.equals(feature);
    }

    public static boolean supportsTarget(String target, Integer stackCount) {
        return target != null && target.matches("\\d{6}") && stackCount == null;
    }

    public static boolean isValidReactionMove(Move move) {
        return move != null
            && move.getTags().contains(MoveTag.PHYSICAL)
            && move.getTags().contains(MoveTag.CURSED_ENERGY)
            && move.getTags().contains(MoveTag.ATTACK)
            && move.getTags().contains(MoveTag.MELEE)
            && move.getTags().contains(MoveTag.SWORD)
            && move.getTags().contains(MoveTag.STUN);
    }

    private static boolean activatesSimpleDomain(Move move) {
        return move.getSelfEffects().stream().anyMatch(NewShadowStyleAbility::isActivation);
    }

    private static boolean isActivation(StatusEffect effect) {
        return effect != null && KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            && ACTIVATE_SIMPLE_DOMAIN.equalsIgnoreCase(effect.getCodedAction());
    }

    private CombatEvent event(int tick, String message) {
        return CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(owner).tick(tick)
            .codedAbilityState(state())
            .message(message)
            .build();
    }
}
