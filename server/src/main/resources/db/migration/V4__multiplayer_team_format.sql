-- v11: multi-fighter (N-fighter) challenge/match setup.
--
-- A challenge now carries a BattleFormat (1v1 or 2v2) and an ordered roster of
-- canonical character ids per side (comma-joined; one entry for 1v1, two for
-- 2v2). Roster-size and duplicate validation is enforced primarily in
-- ChallengeService (Java); the SQL CHECK below is best-effort.

ALTER TABLE challenge ADD COLUMN format VARCHAR(16) NOT NULL DEFAULT 'ONE_V_ONE';
ALTER TABLE challenge ADD COLUMN host_character_ids VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE challenge ADD COLUMN requested_character_ids VARCHAR(80);
ALTER TABLE challenge ADD COLUMN accepted_character_ids VARCHAR(80);

ALTER TABLE challenge ADD CONSTRAINT ck_challenge_format
    CHECK (format IN ('ONE_V_ONE', 'TWO_V_TWO'));

-- Keep the legacy single columns (no longer authoritative) but stop requiring
-- them in the acceptance CHECK: the roster lives in *_character_ids. Existing
-- rows back-fill host_character_ids from host_character_id for continuity.
UPDATE challenge SET host_character_ids = host_character_id
    WHERE host_character_ids = '' AND host_character_id IS NOT NULL;

ALTER TABLE challenge DROP CONSTRAINT ck_challenge_acceptance;
ALTER TABLE challenge ADD CONSTRAINT ck_challenge_acceptance
    CHECK (
        (status = 'ACCEPTED'
            AND accepted_player_id IS NOT NULL
            AND accepted_character_ids IS NOT NULL
            AND accepted_at IS NOT NULL
            AND match_id IS NOT NULL)
        OR
        (status <> 'ACCEPTED'
            AND accepted_player_id IS NULL
            AND accepted_character_ids IS NULL
            AND accepted_at IS NULL
            AND match_id IS NULL)
    );

ALTER TABLE challenge DROP CONSTRAINT ck_challenge_request;
ALTER TABLE challenge ADD CONSTRAINT ck_challenge_request
    CHECK (
        (join_request_id IS NULL
            AND requested_player_id IS NULL
            AND requested_character_ids IS NULL
            AND requested_at IS NULL)
        OR
        (status = 'OPEN'
            AND join_request_id IS NOT NULL
            AND requested_player_id IS NOT NULL
            AND requested_character_ids IS NOT NULL
            AND requested_at IS NOT NULL)
    );

-- match_participant now stores the ordered roster (comma-joined).
ALTER TABLE match_participant ADD COLUMN character_ids VARCHAR(80) NOT NULL DEFAULT '';

UPDATE match_participant SET character_ids = character_id
    WHERE character_ids = '' AND character_id IS NOT NULL;

ALTER TABLE match_participant ALTER COLUMN character_id DROP NOT NULL;
