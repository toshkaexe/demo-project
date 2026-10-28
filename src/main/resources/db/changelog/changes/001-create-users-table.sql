--liquibase formatted sql

--changeset anton:001-create-users-table
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE users;
