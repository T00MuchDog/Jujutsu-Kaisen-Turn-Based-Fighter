# Multiplayer Development and Deployment

The first online multiplayer version uses a Java 17 Javalin server, a shared headless battle engine in `core`, asynchronous Java HTTP/WebSocket clients in `graphics`, and a Flyway-managed SQL database.

## Prerequisites

- JDK 17 or newer
- Maven 3.9 or newer
- Docker only when using the optional local PostgreSQL service

## Fast Local Setup

The server defaults to a persistent embedded H2 database at `server-data/jjktbf`. No separate database process is required for this path.

```bash
mvn -Drevision=1.1.1 -pl server -am clean package
java -jar server/target/server-1.1.1.jar
```

The default endpoints are:

```text
http://localhost:7070
ws://localhost:7070/ws/matches
```

In another terminal, build the desktop client:

```bash
mvn -Drevision=1.1.1 -pl graphics -am package
```

Launch two clients in separate terminals with separate application-data roots so they receive different guest identities.

macOS:

```bash
java -XstartOnFirstThread -Djjktbf.data.root="$PWD/.local/client-a" -jar graphics/target/graphics-1.1.1.jar
java -XstartOnFirstThread -Djjktbf.data.root="$PWD/.local/client-b" -jar graphics/target/graphics-1.1.1.jar
```

Windows/Linux:

```bash
java -Djjktbf.data.root="$PWD/.local/client-a" -jar graphics/target/graphics-1.1.1.jar
java -Djjktbf.data.root="$PWD/.local/client-b" -jar graphics/target/graphics-1.1.1.jar
```

The equivalent environment override is `JJKTBF_DATA_ROOT`. Without an override, normal installed clients continue to use the existing OS-specific application-data directory.

## PostgreSQL Setup

Start the optional local database:

```bash
docker compose up -d database
```

Run the server against it:

```bash
export DATABASE_URL='jdbc:postgresql://localhost:5432/jjktbf'
export DATABASE_USERNAME='jjktbf'
export DATABASE_PASSWORD='jjktbf-local-development'
export AUTH_TOKEN_SECRET='local-only-replace-before-production'
java -jar server/target/server-1.1.1.jar
```

Flyway applies the versioned migrations under `server/src/main/resources/db/migration` at startup. V1 creates the multiplayer schema, V2 adds pending join requests, and V3 preserves accepted request identity for safe retries. To destroy only the local Compose database and its data:

```bash
docker compose down -v
```

## Manual Test Flow

1. Start the database if using PostgreSQL.
2. Start `server/target/server-1.1.1.jar`.
3. Start client A with its own `jjktbf.data.root`.
4. Start client B with a different `jjktbf.data.root`.
5. In A, select `MULTIPLAYER`, choose a fighter, and select `HOST CHALLENGE`.
6. In B, select `MULTIPLAYER`, choose a fighter, select `SEARCH CHALLENGES`, then request to join A.
7. In A, approve the pending request with `YES` (or decline it with `NO`).
8. Both clients transition to the shared authoritative `BattleScreen` after approval.
9. Build each plan in the same two-board timeline planner used by single player and lock it in.
10. After synchronized playback, both players select `NEXT ROUND`; continue until knockout or the round-limit draw.

The host can cancel while a challenge is open, approve or reject one pending requester, and safely retry ambiguous creation/acceptance responses. A requester can hold one pending request, withdraw it, and recover it after reopening the client. A command is not rendered as accepted until the server returns a `MatchState` carrying that exact `commandId`; opponent-only state changes do not acknowledge a local plan.

## Configuration

Server environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `7070` | HTTP and WebSocket listening port. |
| `DATABASE_URL` | File-backed H2 URL | JDBC URL or hosted `postgresql://user:password@host/database` URI. |
| `DATABASE_USERNAME` | `sa` for H2 | Database user. |
| `DATABASE_PASSWORD` | Empty for H2 | Database password. |
| `AUTH_TOKEN_SECRET` | Development-only stable value | HMAC key for guest tokens. Must be replaced and kept stable in production. |
| `CHALLENGE_EXPIRY_MINUTES` | `15` | Open challenge lifetime. |
| `DISCONNECT_TIMEOUT_SECONDS` | `30` | Reconnect grace period before forfeit/abandonment. |
| `MAX_OPEN_CHALLENGES` | `3` | Per-guest open challenge limit. |

Desktop client configuration:

| Setting | Default | Purpose |
| --- | --- | --- |
| `GAME_SERVER_HTTP_URL` | `https://play.jjktbf.com` | HTTP API base URL. |
| `GAME_SERVER_WS_URL` | ` wss://play.jjktbf.com/ws/matches` | Match WebSocket URL. |
| `-Djjktbf.server.http=...` | Environment/default | JVM-property override for HTTP. |
| `-Djjktbf.server.ws=...` | Environment/default | JVM-property override for WebSocket. |
| `-Djjktbf.data.root=...` | OS application-data directory | Isolated local profile root. |



## Production Guidance

- Use PostgreSQL with a dedicated least-privilege database account.
- Generate `AUTH_TOKEN_SECRET` from at least 32 random bytes and keep it unchanged across server restarts. Changing it invalidates existing guest tokens.
- Terminate TLS at a reverse proxy or load balancer and proxy both `/api/*` and `/ws/matches` to the server. Preserve WebSocket upgrade and `Authorization` headers.
- Do not expose the embedded H2 database as a production service.
- Back up PostgreSQL and deploy migrations before or with the matching server release.
- Restrict server logs and database access; raw guest tokens are intentionally never persisted or logged server-side.
- Keep `gameVersion`, `protocolVersion`, and the server-bundled canonical `data` catalog aligned with the desktop release.
- Explicit malformed numeric settings, secrets shorter than 32 bytes, and PostgreSQL deployments using the development secret fail at startup.

## Automated Verification

```bash
mvn clean verify
```

Targeted commands:

```bash
mvn -pl core test
mvn -pl graphics -am test
mvn -pl server -am test
```

Server integration tests start an actual ephemeral Javalin server, create two guests, request and approve a challenge, connect two real Java WebSocket clients, exchange heartbeat messages, submit commands, synchronize the next round, and verify complete-state broadcasts and restart recovery.

## Persistence and Recovery Limits

- Guest accounts, token hashes, challenges, match metadata, participants, and completed results are persistent.
- Active `MatchState` is intentionally in server memory and does not survive a server restart. Interrupted active matches are marked abandoned at startup.
- Persisted `WAITING` matches are reconstructed lazily when either participant retries HTTP setup, acceptance, or WebSocket join.
- Compatible hosted and requested challenges are discoverable after a client restart. Legacy incompatible rows are not offered for recovery.
- A brief socket interruption is retried five times with bounded backoff. The server keeps the match for the configured grace period and sends the newest full state after rejoin.
- A disconnected player forfeits when their grace period expires while the opponent remains connected. If both players remain disconnected, the match is abandoned without a winner.
- The online server trusts only its bundled character/move definitions. Local editor changes remain available to single player but can be rejected online.
