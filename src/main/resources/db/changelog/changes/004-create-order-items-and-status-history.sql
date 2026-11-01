--liquibase formatted sql

--changeset anton:004-create-order-items-table
CREATE TABLE IF NOT EXISTS order_items
(
    id         BIGSERIAL PRIMARY KEY,
    -- CASCADE: items are part of the order and live only with it
    order_id   BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- RESTRICT: a product that was ever ordered can't be deleted, old orders must stay readable
    product_id INTEGER        NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity   INTEGER        NOT NULL CHECK (quantity > 0),
    -- price at the moment of ordering: later price changes must not change old orders
    price      NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    -- one line per product in an order
    UNIQUE (order_id, product_id)
);
-- the unique index above starts with order_id and already serves "items of this order";
-- this one is for the RESTRICT check when a product is deleted
CREATE INDEX order_items_product_id_idx ON order_items (product_id);
--rollback DROP TABLE order_items;

--changeset anton:004-create-order-status-history-table
CREATE TABLE IF NOT EXISTS order_status_history
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- NULL only for the first record, when the order is created
    from_status VARCHAR(20)
        CHECK (from_status IN ('CREATED', 'PROCESSING', 'READY', 'COMPLETED', 'CANCELLED')),
    to_status   VARCHAR(20) NOT NULL
        CHECK (to_status IN ('CREATED', 'PROCESSING', 'READY', 'COMPLETED', 'CANCELLED')),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- who changed the status; NULL is reserved for changes made by the system
    changed_by  BIGINT REFERENCES users (id) ON DELETE RESTRICT,
    CHECK (from_status IS DISTINCT FROM to_status)
);
-- history of one order in time order
CREATE INDEX order_status_history_order_id_idx ON order_status_history (order_id, changed_at);
--rollback DROP TABLE order_status_history;
