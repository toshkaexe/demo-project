--liquibase formatted sql

--changeset anton:005-users-role-check
-- the same list as the Role enum; a warehouse employee is assigned only here, in the database:
-- UPDATE users SET role = 'WAREHOUSE' WHERE email = '...';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'WAREHOUSE'));
--rollback ALTER TABLE users DROP CONSTRAINT users_role_check;
