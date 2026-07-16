package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single explicit WebSocket envelope. Optional numeric fields use wrappers so
 * an absent value is distinguishable from zero.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocketMessage(
    MessageType type,
    String matchId,
    String gameVersion,
    Integer protocolVersion,
    String ruleset,
    String commandId,
    ActionCommand command,
    Long stateVersion,
    MatchState state,
    ErrorResponse error,
    String playerId,
    String playerName,
    PlayerSide playerSide,
    Long heartbeatTimestamp,
    Long disconnectDeadline
) {
    public static SocketMessage joinMatch(String matchId) {
        return joinMatch(
            matchId,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }

    public static SocketMessage joinMatch(
        String matchId,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) {
        return new SocketMessage(
            MessageType.JOIN_MATCH, matchId, gameVersion, protocolVersion, ruleset,
            null, null, null, null, null, null, null, null, null, null
        );
    }

    public static SocketMessage matchJoined(
        String matchId,
        String playerId,
        String playerName,
        PlayerSide playerSide,
        MatchState state
    ) {
        return new SocketMessage(
            MessageType.MATCH_JOINED,
            matchId,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            null,
            null,
            state == null ? null : state.stateVersion(),
            state,
            null,
            playerId,
            playerName,
            playerSide,
            null,
            null
        );
    }

    public static SocketMessage submitAction(ActionCommand command) {
        return new SocketMessage(
            MessageType.SUBMIT_ACTION,
            command == null ? null : command.matchId(),
            null,
            null,
            null,
            command == null ? null : command.commandId(),
            command,
            command == null ? null : command.expectedStateVersion(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static SocketMessage matchState(MatchState state) {
        return matchState(state, null);
    }

    /** State update optionally acknowledging the command accepted for this recipient. */
    public static SocketMessage matchState(MatchState state, String commandId) {
        return new SocketMessage(
            MessageType.MATCH_STATE,
            state == null ? null : state.matchId(),
            null,
            null,
            null,
            commandId,
            null,
            state == null ? null : state.stateVersion(),
            state,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static SocketMessage commandRejected(
        String matchId,
        String commandId,
        ErrorResponse error,
        MatchState state
    ) {
        return new SocketMessage(
            MessageType.COMMAND_REJECTED,
            matchId,
            null,
            null,
            null,
            commandId,
            null,
            state == null ? null : state.stateVersion(),
            state,
            error,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static SocketMessage playerConnected(
        String matchId,
        String playerId,
        String playerName,
        PlayerSide playerSide
    ) {
        return new SocketMessage(
            MessageType.PLAYER_CONNECTED, matchId, null, null, null,
            null, null, null, null, null, playerId, playerName, playerSide, null, null
        );
    }

    public static SocketMessage playerDisconnected(
        String matchId,
        String playerId,
        String playerName,
        PlayerSide playerSide,
        long disconnectDeadline
    ) {
        return new SocketMessage(
            MessageType.PLAYER_DISCONNECTED, matchId, null, null, null,
            null, null, null, null, null, playerId, playerName, playerSide,
            null, disconnectDeadline
        );
    }

    public static SocketMessage matchEnded(MatchState state) {
        return new SocketMessage(
            MessageType.MATCH_ENDED,
            state == null ? null : state.matchId(),
            null,
            null,
            null,
            null,
            null,
            state == null ? null : state.stateVersion(),
            state,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static SocketMessage ping(long heartbeatTimestamp) {
        return heartbeat(MessageType.PING, heartbeatTimestamp);
    }

    public static SocketMessage pong(long heartbeatTimestamp) {
        return heartbeat(MessageType.PONG, heartbeatTimestamp);
    }

    private static SocketMessage heartbeat(MessageType type, long heartbeatTimestamp) {
        return new SocketMessage(
            type, null, null, null, null, null, null, null, null, null,
            null, null, null, heartbeatTimestamp, null
        );
    }

    public static SocketMessage error(ErrorResponse error) {
        return error(null, error);
    }

    public static SocketMessage error(String matchId, ErrorResponse error) {
        return new SocketMessage(
            MessageType.ERROR, matchId, null, null, null, null, null, null,
            null, error, null, null, null, null, null
        );
    }
}
