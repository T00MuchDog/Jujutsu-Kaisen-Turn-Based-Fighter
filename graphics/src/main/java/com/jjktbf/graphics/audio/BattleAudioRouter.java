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
            case DAMAGE_DEALT -> Optional.of(SoundCue.BATTLE_HIT);
            case BLACK_FLASH -> Optional.of(SoundCue.BATTLE_BLACK_FLASH);
            default -> Optional.empty();
        };
    }

    public static Optional<SoundCue> cueFor(BattleEventState event, Move move) {
        Objects.requireNonNull(event, "event");
        return switch (event.type()) {
            case MOVE_FIRED -> cueForMove(move);
            case MOVE_MISSED -> Optional.of(SoundCue.BATTLE_MISS);
            case MOVE_BLOCKED, MOVE_BLOCK_REDUCED -> Optional.of(SoundCue.BATTLE_BLOCK);
            case DAMAGE_DEALT -> Optional.of(SoundCue.BATTLE_HIT);
            case BLACK_FLASH -> Optional.of(SoundCue.BATTLE_BLACK_FLASH);
            default -> Optional.empty();
        };
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
