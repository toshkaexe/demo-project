--liquibase formatted sql

--changeset anton:003-create-orders-table
CREATE TABLE orders
(
    id           BIGSERIAL PRIMARY KEY,
    -- RESTRICT: a user with orders can't be deleted, the order history must not disappear
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    status       VARCHAR(20) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'PROCESSING', 'READY', 'COMPLETED', 'CANCELLED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- set only when the order is completed
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
-- "orders of this user" is the most common query
CREATE INDEX orders_user_id_idx ON orders (user_id);
--rollback DROP TABLE orders;
