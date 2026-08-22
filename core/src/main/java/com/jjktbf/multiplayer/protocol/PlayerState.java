package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Identity, connection, submission, and ordered roster state for one participant. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerState(
    String playerId,
    String displayName,
    PlayerSide side,
    boolean connected,
    boolean readyForBattle,
    boolean planSubmitted,
    boolean readyForNextRound,
    Long disconnectDeadline,
    List<CharacterState> combatants
) {
    public PlayerState {
        combatants = combatants == null ? List.of() : List.copyOf(combatants);
    }

    /** Protocol-v8 constructor for a participant with one canonical character. */
    public PlayerState(
        String playerId,
        String displayName,
        PlayerSide side,
        boolean connected,
        boolean readyForBattle,
        boolean planSubmitted,
        boolean readyForNextRound,
        Long disconnectDeadline,
        CharacterState character
    ) {
        this(
            playerId,
            displayName,
            side,
            connected,
            readyForBattle,
            planSubmitted,
            readyForNextRound,
            disconnectDeadline,
            character == null ? List.of() : List.of(character)
        );
    }

    /** Legacy singular view: the first fighter, or the first roster member. */
    @JsonIgnore
    public CharacterState character() {
        return combatants.stream()
            .filter(combatant -> "FIGHTER".equals(combatant.role()))
            .findFirst()
            .orElse(combatants.isEmpty() ? null : combatants.get(0));
    }
}
