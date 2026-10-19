--liquibase formatted sql

--changeset anton:001-create-users-table
CREATE TABLE users
(
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(254) NOT NULL UNIQUE,
    email_verified_at   TIMESTAMPTZ,
    password_hash       VARCHAR(100) NOT NULL,
    password_changed_at TIMESTAMPTZ,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'PENDING', 'BLOCKED', 'DELETED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE users;
