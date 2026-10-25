# lavka — модуль авторизации

Скелет входа по email и паролю на JWT-токенах.

Подтверждения почты, ролей, сессий, смены пароля и Redis пока нет: их сделаем в следующих итерациях.

## Запуск

```bash
cp .env.example .env              # затем вставить ключ: openssl rand -base64 48
docker compose up -d              # Postgres на localhost:5432, Keycloak на http://localhost:8081 (admin/admin)
./mvnw spring-boot:run            # приложение на http://localhost:8080
```

- **`.env`** хранит `JWT_SECRET`, ключ подписи токенов длиной не меньше 32 байт. Файл не коммитится. Без него приложение не стартует.
- **Схему БД** создаёт Liquibase при старте приложения (`src/main/resources/db/changelog`).
- **Тесты** (`./mvnw test`) поднимают свой Postgres через Testcontainers, поэтому нужен запущенный Docker. Локальная база тестами не затрагивается.

Готовые запросы для IntelliJ лежат в `src/test/api.http`. Перед запуском выберите окружение `dev`.

## Как устроены токены

После регистрации и логина клиент получает пару токенов:

```json
{
  "access_token":  "eyJhbGciOiJIUzUxMiJ9...",
  "refresh_token": "eyJhbGciOiJIUzUxMiJ9..."
}
```

| | access_token | refresh_token |
|---|---|---|
| Для чего | передаётся с каждым запросом в `Authorization: Bearer <token>` | только чтобы получить новую пару в `POST /auth/refresh` |
| Срок жизни | 15 минут (`auth.jwt.access-ttl`) | 30 дней (`auth.jwt.refresh-ttl`) |
| Внутри | `sub` = id пользователя, `typ` = `access` | `sub` = id пользователя, `typ` = `refresh` |

- Оба токена — JWT, подписанные ключом из `.env`. Подделать их нельзя, но прочитать содержимое может кто угодно. Поэтому в токен не кладём ничего секретного.
- Поле `typ` не даёт использовать refresh-токен вместо access-токена и наоборот.
- Все проверки токена собраны в одном методе `JWTService.validateToken(token, expectedType)`. Он проверяет по порядку:
  1. **подпись**: jjwt пересчитывает HMAC от `header.payload` нашим ключом и сравнивает с подписью токена;
  2. **срок**: `exp` не в прошлом;
  3. **тип**: `typ` совпадает с ожидаемым (`access` или `refresh`);
  4. **отзыв**: токен не отозван через logout или refresh.

  На первой же неудаче метод бросает `InvalidTokenException` с причиной (`BAD_SIGNATURE`, `EXPIRED`, `WRONG_TYPE`, `REVOKED`, `MALFORMED`), и клиент получает `401`.
- Отозванные токены (после выхода или обмена refresh-токена) хранятся в памяти приложения до окончания своего срока. **После перезапуска приложения этот список очищается.**

## Таблица `users`

| Колонка | Тип | |
|---|---|---|
| `id` | `BIGSERIAL` | первичный ключ |
| `email` | `VARCHAR(254)` | уникальный, хранится в нижнем регистре |
| `password_hash` | `VARCHAR(100)` | BCrypt-хеш, сам пароль нигде не хранится |
| `created_at` | `TIMESTAMPTZ` | когда создан |
| `updated_at` | `TIMESTAMPTZ` | когда обновлён |

## Ручки

### Регистрация — `POST /auth/register`

Создаёт пользователя и сразу выдаёт пару токенов, так что отдельно логиниться не нужно.

**Запрос:**
```http
POST /auth/register
Content-Type: application/json

{
  "email": "anton@example.com",
  "password": "123456"
}
```

**Как работает:**
1. Проверяет тело: `email` должен быть корректным адресом длиной до 254 символов, `password` — от 6 до 100 символов.
2. Приводит email к нижнему регистру и обрезает пробелы, поэтому `Anton@Example.com` и `anton@example.com` — один и тот же пользователь.
3. Если такой email уже есть, возвращает `409`.
4. Хеширует пароль через BCrypt и сохраняет пользователя в `users`.
5. Выдаёт пару токенов.

**Ответ `201 Created`:**
```json
{ "access_token": "...", "refresh_token": "..." }
```

| Код | Когда |
|---|---|
| `201` | пользователь создан |
| `400` | неверный email, пароль короче 6 символов или не хватает полей |
| `409` | `User already exists`: такой email уже зарегистрирован |

### Логин — `POST /auth/login`

Вход только по email и паролю.

**Запрос:**
```http
POST /auth/login
Content-Type: application/json

{
  "email": "anton@example.com",
  "password": "123456"
}
```

**Как работает:**
1. Находит пользователя по email без учёта регистра.
2. Сверяет пароль с хешем из базы через `BCrypt.matches`.
3. Выдаёт новую пару токенов. Каждый логин даёт отдельную пару, поэтому можно войти с нескольких устройств одновременно.

**Ответ `200 OK`:**
```json
{ "access_token": "...", "refresh_token": "..." }
```

| Код | Когда |
|---|---|
| `200` | вход выполнен |
| `400` | пустой email или пароль |
| `401` | `Invalid email or password`: такого email нет или пароль неверный. Ответ одинаковый в обоих случаях, чтобы по нему нельзя было узнать, зарегистрирован ли email |

### Выход — `POST /auth/logout`

Отзывает оба токена пары: и access, и refresh.

**Запрос:**
```http
POST /auth/logout
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "refresh_token": "<refresh_token>"
}
```

**Как работает:**
1. Проверяет access-токен из заголовка: подпись, срок действия, `typ = access`, токен не отозван.
2. Проверяет refresh-токен из тела: он тоже должен быть действующим и принадлежать **тому же** пользователю.
3. Добавляет оба токена в список отозванных.

Остальные логины этого пользователя, например на другом устройстве, продолжают работать.

**Ответ `204 No Content`** — без тела.

| Код | Когда |
|---|---|
| `204` | выход выполнен |
| `400` | в теле нет `refresh_token` |
| `401` | `Missing bearer token`: нет заголовка. `Token expired`: истёк срок токена. `Invalid token`: токен подделан, отозван или не того типа. `Refresh token belongs to another user`: refresh-токен другого пользователя |

### Текущий пользователь — `GET /users/me`

Возвращает данные пользователя, которому принадлежит access-токен. Тело запроса не нужно.

**Запрос:**
```http
GET /users/me
Authorization: Bearer <access_token>
```

**Как работает:**
1. Берёт токен из заголовка `Authorization: Bearer ...`.
2. Проверяет, что это действующий и не отозванный access-токен.
3. Достаёт из токена id пользователя (`sub`) и загружает пользователя из базы.

**Ответ `200 OK`:**
```json
{
  "id": 1,
  "email": "anton@example.com",
  "created_at": "2026-10-20T18:02:52.773189Z",
  "updated_at": "2026-10-20T18:02:52.773189Z"
}
```

Хеш пароля в ответ не попадает.

| Код | Когда |
|---|---|
| `200` | данные пользователя |
| `401` | `Missing bearer token`: нет заголовка. `Token expired`: access-токен истёк, пора вызвать `/auth/refresh`. `Invalid token`: токен подделан, отозван или передан refresh вместо access. `User not found`: пользователь удалён |

### Обновление пары — `POST /auth/refresh`

Когда access-токен истёк, клиент меняет refresh-токен на новую пару без повторного ввода пароля.

```http
POST /auth/refresh
Content-Type: application/json

{
  "refresh_token": "<refresh_token>"
}
```

- Возвращает `200` и новую пару `{ "access_token", "refresh_token" }`.
- Старый refresh-токен сразу отзывается: каждый refresh-токен можно использовать только один раз.
- Возвращает `401`: `Token expired`, если refresh-токен истёк (нужен новый логин), или `Invalid token`, если он подделан, уже использован или вместо refresh передан access-токен.

## Типичный сценарий

```
register / login  ──►  access_token + refresh_token
        │
        ├─ GET /users/me            (Authorization: Bearer access_token)
        │
        ├─ access_token истёк  ──►  POST /auth/refresh  ──►  новая пара
        │
        └─ POST /auth/logout        (access в заголовке + refresh в теле)  ──►  оба токена отозваны
```

## Формат ошибок

Все ошибки возвращаются в стандартном формате Spring:

```json
{
  "timestamp": "2026-10-20T18:03:15.952Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid email or password",
  "path": "/auth/login"
}
```
