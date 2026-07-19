ALTER TABLE challenge ADD COLUMN accepted_join_request_id VARCHAR(36);

ALTER TABLE challenge ADD CONSTRAINT ck_challenge_accepted_request
    CHECK (accepted_join_request_id IS NULL OR status = 'ACCEPTED');

CREATE UNIQUE INDEX uq_challenge_accepted_join_request
    ON challenge (accepted_join_request_id);
