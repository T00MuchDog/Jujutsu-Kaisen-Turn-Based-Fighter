package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/** Defender responses that occur immediately before an incoming planned move resolves. */
public record CodedMoveResponse(
    boolean fullBlock,
    List<Move> reactionMoves,
    List<CombatEvent> events
) {

    private static final CodedMoveResponse NONE = new CodedMoveResponse(false, List.of(), List.of());

    public CodedMoveResponse {
        reactionMoves = reactionMoves == null ? List.of() : List.copyOf(reactionMoves);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static CodedMoveResponse none() {
        return NONE;
    }

    public CodedMoveResponse combine(CodedMoveResponse other) {
        if (other == null || other == NONE) return this;
        if (this == NONE) return other;
        List<Move> combinedMoves = new ArrayList<>(reactionMoves);
        combinedMoves.addAll(other.reactionMoves);
        List<CombatEvent> combinedEvents = new ArrayList<>(events);
        combinedEvents.addAll(other.events);
        return new CodedMoveResponse(fullBlock || other.fullBlock, combinedMoves, combinedEvents);
    }
}
