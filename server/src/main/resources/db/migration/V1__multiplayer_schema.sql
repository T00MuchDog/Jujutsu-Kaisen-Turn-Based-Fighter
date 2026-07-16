CREATE TABLE guest_player (
    id VARCHAR(36) PRIMARY KEY,
    display_name VARCHAR(24) NOT NULL,
    normalized_display_name VARCHAR(24) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_guest_player_normalized_name UNIQUE (normalized_display_name)
);

CREATE TABLE guest_session (
    id VARCHAR(36) PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT,
    CONSTRAINT uq_guest_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_guest_session_player
        FOREIGN KEY (player_id) REFERENCES guest_player(id)
);

CREATE INDEX idx_guest_session_player ON guest_session (player_id);
CREATE INDEX idx_guest_session_expiry ON guest_session (expires_at, revoked_at);

CREATE TABLE challenge (
    id VARCHAR(36) PRIMARY KEY,
    creator_player_id VARCHAR(36) NOT NULL,
    creator_display_name VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    game_version VARCHAR(32) NOT NULL,
    protocol_version INTEGER NOT NULL,
    ruleset VARCHAR(32) NOT NULL,
    host_character_id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    accepted_player_id VARCHAR(36),
    accepted_character_id VARCHAR(36),
    accepted_at BIGINT,
    match_id VARCHAR(36),
    CONSTRAINT fk_challenge_creator
        FOREIGN KEY (creator_player_id) REFERENCES guest_player(id),
    CONSTRAINT fk_challenge_accepted_player
        FOREIGN KEY (accepted_player_id) REFERENCES guest_player(id),
    CONSTRAINT uq_challenge_match UNIQUE (match_id),
    CONSTRAINT ck_challenge_status
        CHECK (status IN ('OPEN', 'ACCEPTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_challenge_acceptance
        CHECK (
            (status = 'ACCEPTED'
                AND accepted_player_id IS NOT NULL
                AND accepted_character_id IS NOT NULL
                AND accepted_at IS NOT NULL
                AND match_id IS NOT NULL)
            OR
            (status <> 'ACCEPTED'
                AND accepted_player_id IS NULL
                AND accepted_character_id IS NULL
                AND accepted_at IS NULL
                AND match_id IS NULL)
        )
);

CREATE INDEX idx_challenge_listing ON challenge
    (status, game_version, protocol_version, ruleset, expires_at);
CREATE INDEX idx_challenge_creator_status ON challenge
    (creator_player_id, status);

CREATE TABLE match_record (
    id VARCHAR(36) PRIMARY KEY,
    challenge_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    server_seed BIGINT NOT NULL,
    game_version VARCHAR(32) NOT NULL,
    protocol_version INTEGER NOT NULL,
    ruleset VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    ended_at BIGINT,
    CONSTRAINT uq_match_record_challenge UNIQUE (challenge_id),
    CONSTRAINT fk_match_record_challenge
        FOREIGN KEY (challenge_id) REFERENCES challenge(id),
    CONSTRAINT ck_match_record_status
        CHECK (status IN ('WAITING', 'ACTIVE', 'OPPONENT_DISCONNECTED', 'ENDED', 'ABANDONED'))
);

CREATE TABLE match_participant (
    match_id VARCHAR(36) NOT NULL,
    player_id VARCHAR(36) NOT NULL,
    side VARCHAR(16) NOT NULL,
    character_id VARCHAR(36) NOT NULL,
    joined_at BIGINT,
    disconnected_at BIGINT,
    PRIMARY KEY (match_id, player_id),
    CONSTRAINT uq_match_participant_side UNIQUE (match_id, side),
    CONSTRAINT fk_match_participant_match
        FOREIGN KEY (match_id) REFERENCES match_record(id),
    CONSTRAINT fk_match_participant_player
        FOREIGN KEY (player_id) REFERENCES guest_player(id),
    CONSTRAINT ck_match_participant_side
        CHECK (side IN ('PLAYER_ONE', 'PLAYER_TWO'))
);

CREATE INDEX idx_match_participant_player ON match_participant (player_id);

CREATE TABLE match_result (
    match_id VARCHAR(36) PRIMARY KEY,
    winner_player_id VARCHAR(36),
    result_type VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    completed_at BIGINT NOT NULL,
    CONSTRAINT fk_match_result_match
        FOREIGN KEY (match_id) REFERENCES match_record(id),
    CONSTRAINT fk_match_result_winner
        FOREIGN KEY (winner_player_id) REFERENCES guest_player(id),
    CONSTRAINT ck_match_result_type
        CHECK (result_type IN ('VICTORY', 'DRAW', 'FORFEIT', 'ABANDONED'))
);
