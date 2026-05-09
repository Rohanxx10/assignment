# Spring Boot Virality Engine — Grid07 Intern Assignment

A high-performance Spring Boot microservice acting as a central API gateway and guardrail system. It manages users, bots, posts, and nested comments while using **Redis as a real-time atomic gatekeeper** to enforce concurrency rules and prevent AI compute runaway.

---


## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Database | PostgreSQL 15 |
| Cache / Lock Store | Redis 7 (Lettuce) |
| ORM | Hibernate / JPA |
| Build | Maven 3.9 |
| Containers | Docker Compose |

---
### 1. PostMan Collection link
https://rohanrai7217-4854451.postman.co/workspace/Rohan-Rai's-Workspace~4e29352c-e788-4487-8c89-e63ea6229479/collection/53197670-56f54141-9075-4699-b348-30ed2b07800b?action=share&creator=53197670&active-environment=53197670-d6d4b4d6-08e8-4519-9b2f-d63f5fc96dda

## Getting Started

### 1. Start infrastructure

```bash
docker-compose up -d
```

Spins up **PostgreSQL** on `5432` and **Redis** on `6379`.

### 2. Run the application

```bash
./mvnw spring-boot:run
```

Server starts at `http://localhost:8080`.

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/posts` | Create a new post |
| `POST` | `/api/posts/{postId}/comments` | Add a comment (bot guardrails enforced) |
| `POST` | `/api/posts/{postId}/like` | Like a post (+20 virality) |
| `GET`  | `/api/posts/{postId}/stats` | Virality score & bot count |
| `POST` | `/api/v1/user` | Register a user |
| `POST` | `/api/v1/bot` | Register a bot |

---

## Phase 2 — Thread Safety & Atomic Locks

> This section explains exactly how the three Redis guardrails guarantee thread safety under concurrent load.

The core principle: **all state lives in Redis, never in JVM memory**. No `synchronized` blocks, no `HashMap`, no `static` variables. This makes the guardrails correct even across multiple horizontally-scaled application instances.

---

### Lock 1 — Horizontal Cap (max 100 bot replies per post)

**Redis key:** `post:{id}:bot_count`  
**Mechanism: Lua script executed via `RedisTemplate.execute()`**

```lua
-- Executed atomically as a single Redis command
local current = redis.call('INCR', KEYS[1])
if current > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    return -1
end
return current
```

```java
DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_INCR_CAP, Long.class);
Long result = redisTemplate.execute(script, List.of(key), String.valueOf(BOT_HORIZONTAL_CAP));

if (result == null || result == -1L) {
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ...);
}
```

**Why Lua guarantees thread safety:**

A naive `INCR → check → DECR` across three separate Redis calls has a race window: two threads can both `INCR` to 100, both pass the check, and both write to the database — resulting in 101 rows. Lua scripts run **atomically inside Redis**. The entire script — increment, compare, and conditional decrement — is executed as a single, non-interruptible unit. Redis processes no other command between the `INCR` and the `if` check. Under 200 concurrent bot requests the 101st request will always receive `-1` from the script and be rejected before any database write occurs.

---

### Lock 2 — Cooldown Cap (one bot → one user per 10 minutes)

**Redis key:** `cooldown:bot_{id}:human_{id}`  
**Redis command:** `SET NX EX 600`

```java
Boolean wasAbsent = redisTemplate.opsForValue()
    .setIfAbsent(key, "1", Duration.ofSeconds(RedisKeys.COOLDOWN_TTL_SECS));

if (Boolean.FALSE.equals(wasAbsent)) {
    Long ttl = redisTemplate.getExpire(key);
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
        "Bot " + botId + " is on cooldown. Retry in " + ttl + " seconds.");
}
```

**Why it's thread-safe:**  
`setIfAbsent` maps directly to the Redis `SET key value NX EX 600` command. `NX` (Not eXists) and `EX` (expire) are applied in a **single atomic command** — there is no gap between "check if key exists" and "write the TTL" during which a race could occur. The first request claiming the slot wins atomically; all subsequent requests within the 10-minute window are rejected instantly.

---

### Lock 3 — Vertical Cap (max 20 nesting levels)

**No Redis needed.**

```java
int depth = parent.getDepthLevel() + 1;
if (depth > 20) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max depth is 20");
}
```

**Why it's thread-safe:**  
`depth_level` is written **once at comment creation and never mutated**. Reading a parent's depth inside a `@Transactional` method gives a consistent, immutable value. There is no concurrent-write risk because a parent's depth cannot change between reads.

---

### Why Redis Beats Java Locks

| Approach | Problem |
|----------|---------|
| `synchronized` / `ReentrantLock` | Scoped to one JVM — breaks with multiple instances |
| `static HashMap` counter | In-memory per pod — each instance has its own counter |
| **Redis `SET NX EX`** | Single-command atomic — correct across all instances |
| **Redis Lua script** | Multi-step logic executed atomically — zero race window |

---

## Redis Key Reference

| Key | Type | TTL | Purpose |
|-----|------|-----|---------|
| `post:{id}:virality_score` | String (int) | None | Running virality score |
| `post:{id}:bot_count` | String (int) | None | Atomic bot-reply counter (Lua-guarded) |
| `cooldown:bot_{id}:human_{id}` | String | 600s | Per-bot-per-user interaction lock |
| `notif_cooldown:user_{id}` | String | 900s | Notification rate-limit per user |
| `user:{id}:pending_notifs` | List | None | Batched notification queue |

---

## Virality Score System

| Interaction | Points |
|-------------|--------|
| Bot replies to a post | +1 |
| Human likes a post | +20 |
| Human comments on a post | +50 |

---

## Phase 3 — Notification Engine

Bot interactions do **not** send immediate notifications. Instead:

- If the user is **not** on cooldown → log `"Push Notification Sent"` and start a 15-minute cooldown.
- If the user **is** on cooldown → push the message into `user:{id}:pending_notifs` (a Redis List).

A `@Scheduled` task runs every **5 minutes** and sweeps all pending queues:

```
Summarized Push Notification: Bot X and [N] others interacted with your posts.
```

---

## Project Structure

```
src/main/java/com/example/backend/assignment/
├── configuration/
│   ├── RedisConfiguration.java   # Lettuce connection + StringRedisSerializer
│   └── RedisKeys.java            # Centralised key patterns & constants
├── controller/
│   └── UserController.java       # REST endpoints
├── dto/                          # Request / Response DTOs
├── entity/                       # JPA entities: Users, Bot, Post, Comment
├── enums/
│   └── AuthorType.java           # USER | BOT
├── repository/                   # Spring Data JPA repositories
└── service/
    ├── RedisService.java         # All Redis ops + CRON sweeper
    └── UserService.java          # Business logic + DB writes
```

---

## docker-compose.yml

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: assignmentDb
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pgdata:
```
