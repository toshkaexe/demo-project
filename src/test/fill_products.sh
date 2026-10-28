#!/bin/bash
# Fills the products table in the local Postgres from docker-compose.
# There is no products API yet, so rows go straight into the database through psql in the container.
# Safe to run again: a product whose name already exists is skipped.

set -euo pipefail

CONTAINER="lavka-postgres"
PSQL=(docker exec -i "$CONTAINER" psql -U lavka -d lavka -v ON_ERROR_STOP=1 -q)

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "Container $CONTAINER is not running: start it with 'docker compose up -d'" >&2
    exit 1
fi

if [ "$("${PSQL[@]}" -tAc "select to_regclass('public.products') is not null")" != "t" ]; then
    echo "Table products doesn't exist yet: start the application once so Liquibase creates it" >&2
    exit 1
fi

"${PSQL[@]}" <<'SQL'
INSERT INTO products (name, quantity, price)
SELECT v.name, v.quantity, v.price
FROM (VALUES
    ('Молоко 3,2%, 1 л',                 40,  89.99),
    ('Кефир 2,5%, 900 мл',               25,  79.50),
    ('Творог 5%, 200 г',                 30, 119.00),
    ('Сыр Российский, 200 г',            15, 239.90),
    ('Масло сливочное 82,5%, 180 г',     20, 199.00),
    ('Яйца куриные С1, 10 шт',           50, 129.90),
    ('Хлеб Бородинский, 400 г',          35,  59.00),
    ('Батон нарезной, 350 г',            35,  49.90),
    ('Бананы, 1 кг',                     60,  99.90),
    ('Яблоки Гала, 1 кг',                45, 149.00),
    ('Помидоры черри, 250 г',            25, 159.00),
    ('Огурцы, 1 кг',                     30, 189.00),
    ('Картофель, 2 кг',                  40, 119.90),
    ('Куриное филе, 1 кг',               20, 449.00),
    ('Фарш говяжий, 500 г',              15, 329.00),
    ('Гречка, 900 г',                    30,  99.00),
    ('Макароны спагетти, 450 г',         40,  89.00),
    ('Кофе молотый, 250 г',              12, 449.90),
    ('Чай чёрный, 100 пакетиков',        18, 279.00),
    ('Вода минеральная, 1,5 л',         100,  59.90),
    ('Шоколад молочный, 90 г',           50,  99.90),
    ('Мороженое пломбир, 80 г',           0,  69.00)
) AS v(name, quantity, price)
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.name = v.name);
SQL

echo "Products in the table:"
"${PSQL[@]}" -c "select id, name, quantity, price from products order by id"
