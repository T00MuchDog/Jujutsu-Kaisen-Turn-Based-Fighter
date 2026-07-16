package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Result of applying one command to an authoritative match. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResult(
    String commandId,
    boolean accepted,
    ErrorResponse error,
    List<BattleEventState> events,
    MatchState state
) {
    public CommandResult {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static CommandResult accepted(
        String commandId,
        List<BattleEventState> events,
        MatchState state
    ) {
        return new CommandResult(commandId, true, null, events, state);
    }

    public static CommandResult rejected(
        String commandId,
        ErrorResponse error,
        MatchState state
    ) {
        return new CommandResult(commandId, false, error, List.of(), state);
    }
}
