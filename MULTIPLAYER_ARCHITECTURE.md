# Multiplayer Architecture

Last updated: 2026-07-19

## Current Architecture

### Build and modules

The project is a Java 17 Maven reactor.

| Module/path | Current responsibility |
| --- | --- |
| `core` | Character, move, ability, combat, timeline, repository, and JSON DTO code. It has no LibGDX dependency and can run headlessly, although it currently also contains filesystem persistence. |
| `graphics` | LibGDX/LWJGL3 desktop client, screens, battle rendering/input, editors, and executable shaded JAR. Depends on `core`. |
| `data` | Bundled JSON definitions for moves, characters, abilities, and techniques. It is a resource directory, not a Maven module. |

The desktop entry point is `com.jjktbf.graphics.GraphicsMain`. `JJKGame` owns all screens and performs direct navigation. The installable desktop artifact is the shaded `graphics` JAR, later wrapped by `jpackage`.

### Existing local flow

`MainMenuScreen` currently routes `START BATTLE` to `CharacterSelectScreen`. Character selection loads local JSON repositories and chooses a human character followed by a CPU character. `JJKGame.startBattle` creates a background battle thread and invokes `BattleController`, while `BattleScreen` implements the blocking `BattleView` API and posts graphical mutations to the LibGDX thread.

`BattleController` creates two `BattleCombatant` objects and drives:

```text
PLANNING -> RESOLUTION -> ROUND_END -> PLANNING ... -> BATTLE_OVER
```

The human creates a two-board `BattlePlan` in `PlanningPanel`; `GreedyAIStrategy` creates the CPU plan. Both plans are flattened to a legacy `Timeline`, and `CombatResolver` processes CE costs, move ordering, hit rolls, damage, blocks, statuses, interrupts, Black Flash, and victory.

### Relevant domain classes

| Area | Classes |
| --- | --- |
| State | `BattleState`, `BattleCombatant`, `BattlePlan`, `Timeline`, `ActionSegment` |
| Rules | `CombatResolver`, `DamageCalculator`, `PowerCalculator`, `CeEfficiencyCalculator` |
| Content | `Character`, `CharacterData`, `Move`, `MoveData`, `Ability`, `AbilityData`, `StatusEffect` |
| Orchestration | `BattleController`, `AIStrategy`, `GreedyAIStrategy`, `BattleView` |
| Persistence | `AppPaths`, `BaseRepository`, and the character/move/ability/technique repositories |
| Screens | `MainMenuScreen`, `CharacterSelectScreen`, `BattleScreen`, and four editor screens |

### Coupling and extraction findings

The numerical battle rules already have no LibGDX dependency. The following concerns prevent the existing local controller from being used directly by a multiplayer server:

- `BattleController` combines the battle state machine with a blocking one-human/one-AI `BattleView` flow.
- `BattleScreen` combines rendering, local input, animation timing, and thread synchronization.
- `PlanningPanel` performs client-side affordability and move-lock filtering. A server must repeat all validation against canonical content.
- `CombatResolver` stores its resolution cursor in a `ThreadLocal`; all calls for one resolution currently must remain on one thread.
- `BattleState`, plans, events, and combatants are mutable runtime graphs and have no stable wire format.
- `CombatEvent` contains live `BattleCombatant` and `Move` references and must not be serialized directly.
- Content repository IDs are positional and can be resequenced by local editing. Multiplayer therefore uses the server's canonical bundled catalog and a centralized game/protocol version check.
- Local battle and AI randomness use injected `java.util.Random`, but no project-owned random abstraction exists. Equal-speed ordering currently consumes randomness inside a sort comparator and must be made deterministic.
- There is no existing account, HTTP, WebSocket, database, preference, reconnect, or multiplayer UI code.

Existing definition serialization uses Jackson (`MoveData`, `CharacterData`, `AbilityData`, `InnateTechniqueData`). Multiplayer adds explicit live-battle DTOs rather than serializing mutable runtime graphs. JUnit 5 coverage now spans `core`, `graphics`, server services/concurrency, and real HTTP/WebSocket integration.

## Target Module Structure

The existing module names remain to avoid breaking desktop packaging unnecessarily:

```text
project-root/
|-- core/       shared headless engine, multiplayer commands/state/protocol DTOs
|-- graphics/   existing LibGDX desktop client plus async HTTP/WebSocket services
|-- server/     HTTP API, WebSocket endpoint, auth, challenge/match managers, JDBC
`-- data/       canonical content bundled into graphics and server artifacts
```

Dependency direction:

```text
graphics -> core <- server
```

`core` is the practical equivalent of the requested `shared` module. Moving all existing packages into a newly named module would create packaging churn without improving the authority boundary. Filesystem repositories remain in `core` for the current editor/local flow, but the multiplayer engine receives already-built canonical content and performs no filesystem, LibGDX, audio, input, or rendering work.

## Shared Engine Design

New shared concepts live under `com.jjktbf.multiplayer` and use explicit JSON fields rather than polymorphic Java class names:

- `MatchState`: complete versioned wire snapshot.
- `PlayerState`: participant identity, side, connection/readiness, and character state.
- `CharacterState`: HP, CE, derived limits, statuses, BFS, known canonical moves, and current plan.
- `ActionCommand`: `commandId`, `matchId`, `expectedStateVersion`, explicit command type, and plan payload.
- `CommandResult`: accepted/rejected result, optional error, events, and latest snapshot.
- `MatchStatus`: waiting/active/disconnected/ended status.
- `ChallengeSummary`: public challenge, pending-request, accepted-request, and match-link DTO.
- `RoundStartCharacterState`: authoritative HP/CE baseline used for deterministic client playback.
- `RandomSource`: engine-owned random abstraction with a seeded production implementation.
- `HeadlessBattleSession`: canonical plan validation, sequential command application, resolution through the existing combat classes, and immutable snapshot creation.

The existing rules classes are adapted, not copied. Multiplayer commands submit intent as stable move IDs and start ticks. The server looks up moves on the authenticated participant's canonical character, computes CE cost itself, derives the required offensive/defensive board, validates overlap and AP/CE budgets, and constructs the runtime `BattlePlan`. Clients never submit health, damage, resource totals, random outcomes, character definitions, or victory.

The protocol commands are `SUBMIT_PLAN` and `READY_NEXT_ROUND`, matching the game's existing round-based planning and synchronized playback mechanics. A plan payload contains placements such as:

```json
{
  "moveId": "000004",
  "startTick": 13
}
```

Both participants may submit once during each planning phase. Resolution begins only after both accepted plans are present. The server publishes the resolved round as `ROUND_END` with authoritative events and round-start resources, then waits for both `READY_NEXT_ROUND` commands before advancing to a fresh `PLANNING` phase. Empty plans are valid, matching local play. A maximum-round draw guard prevents permanently non-damaging matches.

## State Ownership and Synchronization

The server owns every active `HeadlessBattleSession`, its seed/random source, canonical definitions, submitted plans, processed command IDs, state version, connection state, winner, and timeout state.

For each match, `MatchManager` uses a per-match lock so only one network thread can validate or mutate that session at a time. Different matches can progress independently. A command is accepted only when:

- Its token identifies a participant in the match.
- Its `matchId` matches the joined match.
- The match is active and in a command-compatible phase.
- Its command ID has not already been processed.
- Its expected state version equals the authoritative state version.
- The participant has not already submitted for the current round.
- Every move is known by the participant's canonical character and is not ability-locked.
- Every placement is in bounds, on its derived board, non-overlapping, and within canonical AP and CE budgets.

The version increments exactly once for each accepted command. After each accepted command, both clients receive the latest authoritative `MatchState`, including events produced while resolving the second plan. Before both plans are locked, each viewer-specific snapshot exposes only whether the opponent submitted; the opponent's move segments and derived AP/CE usage remain concealed. The submitting client's update carries its accepted `commandId`, so a state change caused by the opponent cannot be mistaken for local command acceptance. Rejections include a stable code, user-readable message, and latest state where resynchronization is useful. Planning, playback, terminal results, and next-round readiness all render through the shared `BattleScreen`; multiplayer does not maintain a second battle UI.

Active match state is held in server memory and does not survive a server restart. Match metadata, participants, terminal status, and result are persisted. At startup, interrupted active/disconnected matches are marked abandoned. Persisted `WAITING` matches are safe to reconstruct from accepted challenge data and are restored lazily by HTTP setup, accepted retries, or WebSocket join.

## HTTP API

All request/response bodies use JSON. Except guest creation, endpoints require `Authorization: Bearer <guest-token>`. Compatibility fields are centralized in `ProtocolVersion`.

| Method/path | Purpose |
| --- | --- |
| `POST /api/guests` | Create a generated guest identity and private token. |
| `GET /api/session` | Validate the token and return the current guest identity. |
| `POST /api/challenges` | Create one public compatible challenge for the authenticated guest. |
| `GET /api/challenges` | List only open, unexpired, compatible challenges not owned by the caller. |
| `GET /api/challenges/requested` | Recover the caller's compatible pending request or accepted waiting match. |
| `GET /api/challenges/hosted` | Recover the caller's compatible open or accepted hosted challenge. |
| `GET /api/challenges/{challengeId}` | Poll challenge/request status and discover its match ID. |
| `POST /api/challenges/{challengeId}/join` | Place one pending join request with a canonical character. |
| `POST /api/challenges/{challengeId}/accept` | Host-only, request-ID-bound approval and idempotent match creation. |
| `POST /api/challenges/{challengeId}/reject` | Host-only, request-ID-bound rejection. |
| `POST /api/challenges/{challengeId}/withdraw` | Requester-only, request-ID-bound withdrawal. |
| `POST /api/challenges/{challengeId}/cancel` | Cancel an open challenge owned by the caller. |
| `GET /api/matches/{matchId}` | Return setup to a participant, lazily restoring a persisted waiting match. |

Errors use an envelope equivalent to:

```json
{
  "code": "CHALLENGE_ALREADY_ACCEPTED",
  "message": "That challenge is no longer open."
}
```

## WebSocket Protocol

Endpoint: `GET /ws/matches`, upgraded with `Authorization: Bearer <guest-token>`.

The token is validated before a match can be joined and is rechecked for expiry while the socket is active. Production deployment must use TLS (`wss`). Credentials are carried in the upgrade authorization header rather than the request URL, and tokens are never logged.

Client-to-server message types:

- `JOIN_MATCH`: `matchId`, `gameVersion`, `protocolVersion`.
- `SUBMIT_ACTION`: `matchId`, `commandId`, `expectedStateVersion`, and explicit `command`.
- `PING`: heartbeat timestamp.

Server-to-client message types:

- `MATCH_JOINED`: confirms participant/side and carries the complete state.
- `MATCH_STATE`: carries `matchId`, `stateVersion`, viewer-safe state, and the accepted `commandId` only for the submitting client.
- `COMMAND_REJECTED`: structured code/message and latest state when applicable.
- `PLAYER_CONNECTED`: opponent connection notification.
- `PLAYER_DISCONNECTED`: opponent grace-period notification.
- `MATCH_ENDED`: terminal complete state.
- `PONG`: heartbeat response.
- `ERROR`: malformed/auth/protocol errors that are not command rejections.

Message dispatch switches on the explicit `type` field. Unknown types and malformed payloads are rejected without reflecting internal exceptions.

## Challenge and Match Lifecycle

Challenge creation records the creator's selected canonical character. Repeating creation for the same host, character, and compatibility tuple returns the existing open challenge, making response-loss retries safe. A requester places one pending request at a time. The host polls that request and explicitly accepts or rejects it; guest display names are not required in this prompt.

Every request has a unique `joinRequestId`. Accept, reject, and withdraw operations carry that ID so delayed actions cannot target a replacement request. Acceptance preserves it as `acceptedJoinRequestId`, conditionally changes `OPEN` to `ACCEPTED`, inserts the match and both participants in one transaction, and links the match ID. Identical accepted retries reuse the persisted match; stale retries are rejected. Concurrent acceptance and withdrawal therefore have one consistent winner.

Both host and requester poll `GET /api/challenges/{id}`. Approval leads both clients to the same match. Authenticated `hosted` and `requested` recovery endpoints restore screen state after client restart, while compatibility filtering prevents upgraded clients from being trapped on legacy protocol rows.

An open challenge expires after `CHALLENGE_EXPIRY_MINUTES`. A per-player open-challenge limit prevents spam, and listings are capped at 100 rows. Only the creator can cancel, and a player cannot accept their own challenge.

## Disconnects and Reconnection

Closing a match WebSocket marks that participant disconnected, broadcasts `PLAYER_DISCONNECTED`, and schedules a grace timeout. Token expiry also closes an otherwise idle socket at its absolute expiry time. The match remains authoritative and unchanged during the grace period. Reconnecting with the same valid guest token and `JOIN_MATCH` cancels the timeout, marks the player connected, and sends the latest viewer-safe state.

The desktop client uses bounded exponential backoff, heartbeat `PING`/`PONG`, and a generation token so callbacks from an old socket cannot mutate a new screen session. After `DISCONNECT_TIMEOUT_SECONDS`, the server records a forfeit in favor of the connected opponent. If both remain disconnected, the match is abandoned without a winner.

## Database

Flyway migrations create the following tables (SQL names may be snake case):

| Entity/table | Important fields |
| --- | --- |
| `guest_player` | ID, generated display name, creation time. |
| `guest_session` | ID, player ID, hashed token, creation/expiry/revocation data. Raw tokens are never stored. |
| `challenge` | Creator/character, compatibility, pending request ID/player/character/time, accepted request identity/player/character/time, status, and match ID. |
| `match_record` | ID, challenge ID, status, seed, compatibility, ruleset, creation/start/end timestamps. |
| `match_participant` | Match ID, player ID, side, selected character, join/disconnect timestamps. |
| `match_result` | Match ID, winner player ID (nullable for draw/abandonment), result type, reason, completion time. |

Local development defaults to an embedded file-backed H2 database in PostgreSQL compatibility mode. Production uses PostgreSQL through `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Flyway applies versioned migrations; production does not use destructive automatic schema generation.

Configuration also includes `SERVER_PORT`, `AUTH_TOKEN_SECRET`, `CHALLENGE_EXPIRY_MINUTES`, `DISCONNECT_TIMEOUT_SECONDS`, `GAME_SERVER_HTTP_URL`, and `GAME_SERVER_WS_URL`. No real credentials are committed.

## Client Architecture

Networking is outside LibGDX screens:

- `HttpApiClient`: Java 17 asynchronous HTTP/JSON transport with timeout and structured errors.
- `GuestAccountService`: creates/validates guest identities and persists them under `AppPaths`.
- `ChallengeService`: create/list/recover/join/accept/reject/withdraw/cancel operations and ambiguous-response recovery.
- `MatchWebSocketClient`: explicit protocol parsing, heartbeat, bounded reconnect, and close lifecycle.
- `MultiplayerMatchService`: joins a match, tracks the latest state, creates versioned commands, and exposes listeners.
- `MultiplayerSession`: current guest, challenge, match, and connection state.

No network request blocks the LibGDX render thread. Screen listeners receive callbacks through `Gdx.app.postRunnable`.

New/adapted screens:

- `MainMenuScreen`: `SINGLE PLAYER` retains `showCharacterSelect`; `MULTIPLAYER` opens the online menu.
- `MultiplayerMenuScreen`: host, search, and back.
- `HostChallengeScreen`: identity, selected roster entry, recoverable/idempotent hosting, pending-request approval, cancel, and match transition.
- `ChallengeBrowserScreen`: compatible open rows, request/withdraw/poll/recovery states, and requester match transition.
- `BattleScreen`: shared local/online timeline planning, perspective-aware rendering, authoritative event playback, synchronized next rounds, and terminal results.
- `MultiplayerDisconnectedScreen`: reconnect status, bounded retry outcome, and return navigation.

## Security and Logging

- Guest tokens contain at least 256 bits of server-generated entropy.
- The database stores a keyed token hash using `AUTH_TOKEN_SECRET`, not raw tokens.
- Player IDs alone never authenticate requests.
- Server logs include lifecycle IDs and rejection codes but omit raw tokens and request authorization headers.
- API errors never expose stack traces, SQL details, secrets, or internal exception messages.
- Compatibility is checked on challenge creation, recovery, acceptance, HTTP match restoration, and WebSocket join.
- Host decisions and requester withdrawal are bound to unique join request IDs; accepted retries must present the preserved accepted ID.

## Implementation Progress

| Stage | Status | Notes |
| --- | --- | --- |
| Repository/build/battle/UI/randomness/test audit | Complete | Existing architecture and extraction risks documented above. |
| Shared protocol and headless session | Complete | `HeadlessBattleSession` validates canonical plan intent and emits complete immutable snapshots. |
| Random abstraction and deterministic ordering | Complete | `RandomSource`/`SeededRandomSource` own all battle rolls; tie keys are generated only within actual equal-priority groups. |
| Server module and migrations | Complete | Runnable shaded Javalin server, HikariCP, Flyway V1-V3 schema, H2 development, PostgreSQL production support. |
| Guest auth and challenge HTTP API | Complete | HMAC token hashes, persistent guests, host approval, request-ID-bound decisions, recovery, and atomic acceptance/withdrawal. |
| Match manager and WebSocket | Complete | Per-match serialization, viewer-safe broadcasts, heartbeat, reconnect grace, forfeit, retryable result persistence, and cleanup. |
| Desktop networking/session layer | Complete | Async Java 17 HTTP/WebSocket, private guest store, structured errors, heartbeat, and bounded reconnect. |
| Menus and multiplayer screens | Complete | Single-player preserved; host, browser, authoritative battle, and recovery screens implemented. |
| Reconnection, integration tests, documentation | Complete | Real two-client transport integration tests, Compose setup, production configuration, and runbook added. |

## Version 1 Limitations

- Active in-memory match state does not survive a server restart and is marked abandoned; persisted waiting matches can be reconstructed.
- The canonical online catalog is the server-bundled `data` set. Locally edited definitions remain available in single player but are not trusted online.
- The existing resolver still executes a merged representation of the two planning boards. Multiplayer validation preserves board occupancy and budget rules before that existing resolver runs.
- Status types that are inert in the current local engine remain inert online; multiplayer does not invent different status semantics.
- Match snapshots are authoritative state transfers, not a long-term replay/event-sourcing format. The seed and ordered accepted command IDs are retained to support a later replay implementation.
- Online and local plan construction share the drag-and-drop `PlanningPanel`; online budgets, CE costs, availability, and final acceptance remain server-authoritative.
- Character selection is sourced from the local roster for display, then validated against the server's canonical catalog. A locally resequenced/edited ID can be rejected with `INVALID_CHARACTER`.
