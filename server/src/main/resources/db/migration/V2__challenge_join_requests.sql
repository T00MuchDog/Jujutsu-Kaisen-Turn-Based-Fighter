ALTER TABLE challenge ADD COLUMN join_request_id VARCHAR(36);
ALTER TABLE challenge ADD COLUMN requested_player_id VARCHAR(36);
ALTER TABLE challenge ADD COLUMN requested_character_id VARCHAR(36);
ALTER TABLE challenge ADD COLUMN requested_at BIGINT;

ALTER TABLE challenge ADD CONSTRAINT fk_challenge_requested_player
    FOREIGN KEY (requested_player_id) REFERENCES guest_player(id);

ALTER TABLE challenge ADD CONSTRAINT ck_challenge_request
    CHECK (
        (join_request_id IS NULL
            AND requested_player_id IS NULL
            AND requested_character_id IS NULL
            AND requested_at IS NULL)
        OR
        (status = 'OPEN'
            AND join_request_id IS NOT NULL
            AND requested_player_id IS NOT NULL
            AND requested_character_id IS NOT NULL
            AND requested_at IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_challenge_requested_player ON challenge (requested_player_id);
CREATE UNIQUE INDEX uq_challenge_join_request ON challenge (join_request_id);
