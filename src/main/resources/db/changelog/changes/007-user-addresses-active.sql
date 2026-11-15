--liquibase formatted sql

--changeset anton:007-user-addresses-active
-- the address orders are delivered to; a user picks it from the list
ALTER TABLE user_addresses ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE;
-- addresses saved before this change: the oldest one of each user becomes active
UPDATE user_addresses a
SET active = TRUE
WHERE a.id = (SELECT min(b.id) FROM user_addresses b WHERE b.user_id = a.user_id);
-- at most one active address per user
CREATE UNIQUE INDEX user_addresses_one_active_idx ON user_addresses (user_id) WHERE active;
--rollback DROP INDEX user_addresses_one_active_idx;
--rollback ALTER TABLE user_addresses DROP COLUMN active;
