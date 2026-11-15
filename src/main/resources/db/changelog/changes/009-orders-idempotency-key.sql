--liquibase formatted sql

--changeset anton:009-orders-idempotency-key
-- Idempotency-Key of POST /orders: a retry with the same key (lost response, second click) gets the order that was
-- already placed instead of a second one. The key belongs to the user: another user's equal key is a different
-- order. request_hash (SHA-256 of the request, hex) tells a real retry from a reuse of the key for another order.
-- Orders placed without the header have neither.
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN request_hash    VARCHAR(64);
ALTER TABLE orders
    ADD CONSTRAINT orders_idempotency_key_with_hash CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    ADD CONSTRAINT orders_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    -- the last line of defence: two parallel requests with one key can't both save an order;
    -- NULLs are distinct, so orders without a key are not affected
    ADD CONSTRAINT orders_user_idempotency_key_unique UNIQUE (user_id, idempotency_key);
--rollback ALTER TABLE orders DROP CONSTRAINT orders_user_idempotency_key_unique, DROP CONSTRAINT orders_idempotency_key_format, DROP CONSTRAINT orders_idempotency_key_with_hash;
--rollback ALTER TABLE orders DROP COLUMN request_hash, DROP COLUMN idempotency_key;
