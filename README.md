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
| `GET` | `/api/posts/{postId}/stats` | Virality score & bot count |
| `POST` | `/api/v1/user` | Register a user |
| `POST` | `/api/v1/bot` | Register a bot |

---

## Phase 2 — Thread Safety & Atomic Locks

> This section explains exactly how the three Redis guardrails guarantee thread safety under concurrent load.

The core principle: **all state lives in Redis, never in JVM memory**. No `synchronized` blocks, no `HashMap`, no `static` variables. This means the guardrails work correctly even across multiple horizontally-scaled application instances.

---

### Lock 1 — Horizontal Cap (max 100 bot replies per post)

**Redis key:** `post:{id}:bot_count`  
**Redis command:** `INCR`

```java
public void checkAndIncrementBotCount(String postId) {
    String key = RedisKeys.botCount(postId);
    Long newCount = redisTemplate.opsForValue().increment(key);

    if (newCount != null && newCount > RedisKeys.BOT_HORIZONTAL_CAP) {
        redisTemplate.opsForValue().decrement(key);
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ...);
    }
}
```

**Why it's thread-safe:**  
Redis `INCR` is a single-threaded, atomic operation — it increments the counter and returns the new value in one indivisible step. Under 200 concurrent bot requests, Redis processes each `INCR` serially. The 101st request will always receive the value `101`, trigger the decrement, and be rejected. The PostgreSQL write only happens **after** the Redis check passes, so the database will never exceed 100 bot rows per post.

---

### Lock 2 — Cooldown Cap (one bot → one user per 10 minutes)

**Redis key:** `cooldown:bot_{id}:human_{id}`  
**Redis command:** `SET NX EX 600`

```java
public void checkAndSetCooldown(String botId, String userId) {
    String key = RedisKeys.cooldown(botId, userId);
    Boolean wasAbsent = redisTemplate.opsForValue()
        .setIfAbsent(key, "1", Duration.ofSeconds(600));

    if (Boolean.FALSE.equals(wasAbsent)) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ...);
    }
}
```

**Why it's thread-safe:**  
`setIfAbsent` maps to Redis `SET key value NX EX 600` — **NX** (Not eXists) and **EX** (expire) are applied in a single atomic command. There is no gap between "check if key exists" and "set the TTL" during which a race condition could occur. The first bot request claiming the slot wins; all subsequent requests within the 10-minute window are rejected instantly.

---

### Lock 3 — Vertical Cap (max 20 nesting levels)

**No Redis key needed.**

```java
depth = parent.getDepthLevel() + 1;
if (depth > 20) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max depth is 20");
}
```

**Why it's thread-safe:**  
`depth_level` on a comment is written **once at creation and never updated**. Reading a parent's depth inside a `@Transactional` method gives a consistent, immutable value. There is no concurrent-write risk because the parent's depth cannot change between reads.

---

### Why Redis Beats Java Locks

| Approach | Problem |
|----------|---------|
| `synchronized` / `ReentrantLock` | Scoped to a single JVM — breaks with multiple instances |
| `static HashMap` counter | In-memory, not shared — each pod has its own counter |
| **Redis atomic commands** | Single-threaded server, shared across all instances — correct by design |

---

## Redis Key Reference

| Key | Type | TTL | Purpose |
|-----|------|-----|---------|
| `post:{id}:virality_score` | String (int) | None | Running virality score |
| `post:{id}:bot_count` | String (int) | None | Atomic bot-reply counter |
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
