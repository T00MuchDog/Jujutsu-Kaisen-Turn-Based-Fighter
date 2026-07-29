package com.jjktbf.graphics.audio;

import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.multiplayer.protocol.BattleEventState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure mapping from local/online battle events to presentation-only sound cues. */
public final class BattleAudioRouter {
    /** Add stable move-ID overrides here; all other moves use their category cue. */
    private static final Map<String, SoundCue> MOVE_UNLEASH_CUES = Map.of();

    private BattleAudioRouter() {
    }

    public static Optional<SoundCue> cueFor(CombatEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event.getType()) {
            case MOVE_FIRED -> cueForMove(event.getMove());
            case MOVE_MISSED -> Optional.of(SoundCue.BATTLE_MISS);
            case MOVE_BLOCKED, MOVE_BLOCK_REDUCED -> Optional.of(SoundCue.BATTLE_BLOCK);
            case MOVE_DODGED -> Optional.of(SoundCue.BATTLE_DODGE);
            case MOVE_PARRIED -> Optional.of(SoundCue.BATTLE_PARRY);
            case MOVE_STUNNED -> Optional.of(SoundCue.BATTLE_STUN);
            case DAMAGE_DEALT -> Optional.of(SoundCue.BATTLE_HIT);
            case DAMAGE_IGNORED -> Optional.of(SoundCue.BATTLE_DAMAGE_IGNORED);
            case HP_RESTORED -> positiveCue(event.getIntValue(), SoundCue.BATTLE_HEAL);
            case MAX_HP_CHANGED, MAX_CE_CHANGED -> Optional.empty();
            case BLACK_FLASH -> Optional.of(SoundCue.BATTLE_BLACK_FLASH);
            case CE_DRAINED -> event.getMove() == null
                ? positiveCue(event.getIntValue(), SoundCue.BATTLE_CE_DRAIN) : Optional.empty();
            case CE_RESTORED -> positiveCue(event.getIntValue(), SoundCue.BATTLE_CE_RESTORE);
            case CE_DEPLETED -> event.getMove() == null
                ? Optional.empty() : Optional.of(SoundCue.BATTLE_STUN);
            case STATUS_APPLIED -> Optional.of(SoundCue.BATTLE_STATUS_APPLY);
            case STATUS_EXPIRED, BFS_EXPIRED -> Optional.of(SoundCue.BATTLE_STATUS_EXPIRE);
            case ABILITY_ACTIVATED -> Optional.of(SoundCue.BATTLE_ABILITY);
            case RATIO_TRIGGERED -> Optional.of(SoundCue.BATTLE_RATIO);
            case ROUND_END -> Optional.of(SoundCue.BATTLE_ROUND_END);
            case BATTLE_OVER -> Optional.empty();
        };
    }

    public static Optional<SoundCue> cueFor(BattleEventState event, Move move) {
        Objects.requireNonNull(event, "event");
        return switch (event.type()) {
            case MOVE_FIRED -> cueForMove(move);
            case MOVE_MISSED -> Optional.of(SoundCue.BATTLE_MISS);
            case MOVE_BLOCKED, MOVE_BLOCK_REDUCED -> Optional.of(SoundCue.BATTLE_BLOCK);
            case MOVE_DODGED -> Optional.of(SoundCue.BATTLE_DODGE);
            case MOVE_PARRIED -> Optional.of(SoundCue.BATTLE_PARRY);
            case MOVE_STUNNED -> Optional.of(SoundCue.BATTLE_STUN);
            case DAMAGE_DEALT -> Optional.of(SoundCue.BATTLE_HIT);
            case DAMAGE_IGNORED -> Optional.of(SoundCue.BATTLE_DAMAGE_IGNORED);
            case HP_RESTORED -> positiveCue(event.value(), SoundCue.BATTLE_HEAL);
            case MAX_HP_CHANGED, MAX_CE_CHANGED -> Optional.empty();
            case BLACK_FLASH -> Optional.of(SoundCue.BATTLE_BLACK_FLASH);
            case CE_DRAINED -> event.moveId() == null
                ? positiveCue(event.value(), SoundCue.BATTLE_CE_DRAIN) : Optional.empty();
            case CE_RESTORED -> positiveCue(event.value(), SoundCue.BATTLE_CE_RESTORE);
            case CE_DEPLETED -> event.moveId() == null
                ? Optional.empty() : Optional.of(SoundCue.BATTLE_STUN);
            case STATUS_APPLIED, BFS_ENTERED -> Optional.of(SoundCue.BATTLE_STATUS_APPLY);
            case STATUS_EXPIRED, BFS_EXPIRED -> Optional.of(SoundCue.BATTLE_STATUS_EXPIRE);
            case ABILITY_ACTIVATED -> Optional.of(SoundCue.BATTLE_ABILITY);
            case RATIO_TRIGGERED -> Optional.of(SoundCue.BATTLE_RATIO);
            case ROUND_END -> Optional.of(SoundCue.BATTLE_ROUND_END);
            case MOVE_STARTED, ROUND_START, BATTLE_OVER -> Optional.empty();
        };
    }

    private static Optional<SoundCue> positiveCue(Integer value, SoundCue cue) {
        return value != null && value > 0 ? Optional.of(cue) : Optional.empty();
    }

    public static Optional<SoundCue> cueForMove(Move move) {
        if (move == null) return Optional.empty();

        SoundCue moveSpecificCue = MOVE_UNLEASH_CUES.get(move.getId());
        if (moveSpecificCue != null) return Optional.of(moveSpecificCue);
        if (move.isDefensive() || move.hasTag("DEFENSIVE")) {
            return Optional.of(SoundCue.BATTLE_DEFENSE_UNLEASH);
        }
        if (move.getCategory() == MoveCategory.UTILITY) {
            return Optional.of(SoundCue.BATTLE_UTILITY_UNLEASH);
        }
        return Optional.of(SoundCue.BATTLE_ATTACK_UNLEASH);
    }
}
