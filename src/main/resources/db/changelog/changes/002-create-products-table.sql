--liquibase formatted sql

--changeset anton:002-create-products-table
CREATE TABLE products  NOT EXISTS
(
    id       SERIAL PRIMARY KEY,
    name     VARCHAR(255)   NOT NULL,
    quantity INTEGER        NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    price    NUMERIC(10, 2) NOT NULL CHECK (price >= 0)
);
--rollback DROP TABLE products;
