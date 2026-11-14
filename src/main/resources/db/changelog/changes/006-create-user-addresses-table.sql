--liquibase formatted sql

--changeset anton:006-create-user-addresses-table
CREATE TABLE IF NOT EXISTS user_addresses
(
    id             BIGSERIAL PRIMARY KEY,
    -- CASCADE: addresses have no meaning without their user
    user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- no format check: formats differ between countries ("101000", "SW1A 1AA")
    postal_code    VARCHAR(20)  NOT NULL CHECK (btrim(postal_code) <> ''),
    country        VARCHAR(100) NOT NULL CHECK (btrim(country) <> ''),
    city           VARCHAR(100) NOT NULL CHECK (btrim(city) <> ''),
    street         VARCHAR(300) NOT NULL CHECK (btrim(street) <> ''),
    -- text, not a number: "12к2", "5А", "10 стр. 3"
    house          VARCHAR(20)  NOT NULL CHECK (btrim(house) <> ''),
    -- NULL for a private house
    apartment      VARCHAR(20) CHECK (apartment IS NULL OR btrim(apartment) <> ''),
    -- first and last name of whoever receives the order
    recipient_name VARCHAR(200) NOT NULL CHECK (btrim(recipient_name) <> ''),
    courier_note   VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- "addresses of this user" is the main query
CREATE INDEX user_addresses_user_id_idx ON user_addresses (user_id);
--rollback DROP TABLE user_addresses;
