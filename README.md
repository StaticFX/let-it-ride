# Let It Ride

A Flip 7-style push-your-luck card game. A Kotlin/Ktor backend holds the rules
and serves the React frontend from the same process, so deploying it is one
container and one port.

```
┌──────────────────────── one container ────────────────────────┐
│  Ktor (Netty) :8080                                           │
│    GET  /                 → the SPA, bundled into the jar     │
│    GET  /api/catalog      → decks, cards, house rules         │
│    POST /api/rooms        → open a table                      │
│    WS   /ws/{code}        → play                              │
│                                                               │
│  Rooms live in memory. No database.                           │
└───────────────────────────────────────────────────────────────┘
```

The backend is the only thing that knows the rules. Clients send intents
(`HIT`, `STAY`, `PLAY_ACTION`) and render the state and events they get back;
the deck itself is never sent to a client, only its size.

## Run it

### Docker (what you want on a homelab)

[`docker-compose.yml`](docker-compose.yml) is ready to go — set `<owner>` and:

```sh
docker compose up -d
```

Or without compose:

```sh
docker run -d --name let-it-ride --restart unless-stopped \
  -p 8080:8080 ghcr.io/<owner>/let-it-ride:latest
```

Then open `http://<host>:8080`. Nothing to configure; `PORT` and `JAVA_OPTS` are
the only knobs, and there are no volumes because there is nothing to persist.

Behind a reverse proxy, make sure `/ws` is allowed to upgrade — the compose file
has snippets for Traefik, Caddy and nginx.

Images are built for `linux/amd64` and `linux/arm64` and pushed by
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) once the backend tests and
frontend checks pass. Tags: `latest` on the default branch, `sha-<short>` for
every build, and `1.2.3` / `1.2` / `1` for `v*.*.*` git tags.

### Locally, all in one jar

```sh
./gradlew :backend:buildFrontend :backend:buildFatJar
java -jar backend/build/libs/let-it-ride.jar
```

### Locally, with hot reload

Two terminals:

```sh
./gradlew :backend:run                 # rules on :8080
bun --cwd frontend run dev             # UI on :5173, proxying /api and /ws
```

## Layout

```
backend/
  src/main/kotlin/com/letitride/
    engine/    the whole rulebook — pure, no I/O, no coroutines
      Model.kt        cards, players, state, actions
      Engine.kt       the reducer, plus the context cards act through
      CardDefs.kt     what each action and modifier card does
      DeckPresets.kt  Flip 7, Let It Ride, Chaos, …
      LobbyRules.kt   optional house rules
    server/
      Rooms.kt   in-memory rooms, the pacing clock, and the bots
      Dto.kt     the wire types (the client's view redacts the deck)
    Application.kt
  src/test/kotlin/     81 tests, including full games under every preset
frontend/
  src/game/types.ts    mirrors the wire types; decides nothing
  src/net/client.ts    REST + WebSocket, with reconnect
  src/state/           a mirror of server state, nothing more
  src/components/
docs/RULES-AUDIT.md    what was wrong with the rules, and what changed
features/             the original feature notes this was built against
```

## The game

Everyone flips a card in turn. Draw a number you already hold and you bust —
the round scores nothing. Go out when you like and bank what you have.

- **Flip 7** — collect seven different numbers and the round ends for everyone,
  with a 15 point bonus.
- **Scoring** — number cards, then ×2 if you hold it, then the flat modifiers,
  then the Flip 7 bonus.
- **Action cards** resolve the moment you draw them; you pick who they hit.
- **Turn clock** — run out of time and you go out. If you were holding an action
  card, a random player gets it.

Full rules are in the app (the *rules* button) and the deviations from published
Flip 7 are documented in [`docs/RULES-AUDIT.md`](docs/RULES-AUDIT.md).

## Testing

```sh
./gradlew :backend:test              # engine
bun --cwd frontend run typecheck
bun --cwd frontend run lint
```

The engine suite plays complete games with every deck preset, every house rule
and 25 different shuffles, asserting after every transition that no card was
created or destroyed and that no surviving hand holds a duplicate.
