--liquibase formatted sql

--changeset anton:008-orders-delivery-address
-- a copy of the delivery address taken when the order is placed: editing or deleting the address later
-- doesn't change where an old order goes, so there is no foreign key to user_addresses
ALTER TABLE orders
    ADD COLUMN delivery_recipient_name VARCHAR(200),
    ADD COLUMN delivery_postal_code    VARCHAR(20),
    ADD COLUMN delivery_country        VARCHAR(100),
    ADD COLUMN delivery_city           VARCHAR(100),
    ADD COLUMN delivery_street         VARCHAR(300),
    ADD COLUMN delivery_house          VARCHAR(20),
    ADD COLUMN delivery_apartment      VARCHAR(20),
    ADD COLUMN delivery_courier_note   VARCHAR(500);
-- orders placed before addresses existed have no address at all; otherwise every required part is there
ALTER TABLE orders
    ADD CONSTRAINT orders_delivery_address_complete CHECK (
        num_nulls(delivery_recipient_name, delivery_postal_code, delivery_country,
                  delivery_city, delivery_street, delivery_house) IN (0, 6)
        AND (delivery_house IS NOT NULL OR (delivery_apartment IS NULL AND delivery_courier_note IS NULL)));
--rollback ALTER TABLE orders DROP CONSTRAINT orders_delivery_address_complete;
--rollback ALTER TABLE orders DROP COLUMN delivery_recipient_name, DROP COLUMN delivery_postal_code, DROP COLUMN delivery_country, DROP COLUMN delivery_city, DROP COLUMN delivery_street, DROP COLUMN delivery_house, DROP COLUMN delivery_apartment, DROP COLUMN delivery_courier_note;
