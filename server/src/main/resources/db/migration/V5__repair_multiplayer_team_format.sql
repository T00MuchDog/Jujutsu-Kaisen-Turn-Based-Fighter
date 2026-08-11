-- Team rosters are stored in host_character_ids as of V4. Keep the legacy
-- single-character column for migrated rows, but do not require it on new rows.
ALTER TABLE challenge ALTER COLUMN host_character_id DROP NOT NULL;

-- Every current enum value (ONE_V_ONE and TWO_V_TWO) is nine characters.
ALTER TABLE challenge ALTER COLUMN format TYPE VARCHAR(16);
