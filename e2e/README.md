# End-to-end tests

Playwright, driving a real browser against the artefact we ship: one jar
serving both the API and the built SPA on one port. Nothing here stubs the
server or reaches into the client's store — a spec clicks what a player clicks
and reads what a player reads.

```sh
cd e2e
bun install
bun run install-browsers     # once
bun run test
```

The first run builds `frontend/dist` and the fat jar (a minute or two), then
serves it on `:8099` and waits for `/api/health`. After that:

```sh
E2E_SKIP_BUILD=1 bun run test        # reuse the jar you already built
bun run test tests/lobby.spec.ts     # one file
bun run test -g "flip 7"             # one test
bun run test:headed                  # watch it happen
bun run test:ui                      # pick tests, step through them
bun run report                       # last run's HTML report
```

Against a server that is already up — a dev server, a container, a staging box:

```sh
E2E_BASE_URL=http://localhost:5173 bun run test
```

| Variable        | Default                 | What it does                                     |
| --------------- | ----------------------- | ------------------------------------------------ |
| `E2E_BASE_URL`  | —                       | Test this server instead of starting one         |
| `E2E_PORT`      | `8099`                  | Port for the server the suite starts             |
| `E2E_SKIP_BUILD`| `0`                     | Serve the existing jar rather than rebuilding it |

## What is covered

| File                    | What it holds the line on                                                     |
| ----------------------- | ----------------------------------------------------------------------------- |
| `api.spec.ts`           | The SPA shell and its assets, the catalog, opening and looking up rooms, bad input, the seed hook |
| `lobby.spec.ts`         | Naming, hosting, joining, bots, kicking, deck and house-rule settings, the rules book, sound, a server that is down |
| `multiplayer.spec.ts`   | Two browsers at one table; host-only controls; and the socket protocol, including what it refuses |
| `gameplay.spec.ts`      | A round played through the UI, card conservation, a game to the final standings, the pause menu |
| `scenarios.spec.ts`     | Action-card targeting, busting and flip 7 — reached deliberately, see below   |
| `resilience.spec.ts`    | Reconnecting, giving up, the turn clock, a burst of clicks                     |

## Determinism

A room's shuffles come from its seed, and the server will take a seed from a
client when it is started with `LETITRIDE_TEST_HOOKS=1` — which is how the
suite's own server runs, and how nothing else ever should. CI checks the
published image reports `"testHooks":false`.

Everything else about a run is already fixed: the players sit down in the same
order and the local player follows the same policy. So a seed that produced a
bust once produces it every time, and `scenarios.spec.ts` can assert on a flip 7
rather than hope for one.

The seeds live in `support/seeds.ts`, each recording the setup it depends on.
Change the deck, the number of bots or the policy and the seed stops meaning
anything. To find new ones, with a server up:

```sh
node --experimental-strip-types scripts/find-seeds.ts chaos 1 24
```

`scripts/find-seeds.ts` plays a round over the socket for each seed and reports
what happened. It is a tool, not a test; nothing runs it automatically.

## How the specs are built

- **`support/app.ts`** — one player's browser: the title card, the waiting
  room, settings, starting a game. `app.start()` sits through the countdown and
  the deal; `app.startAndWatch()` hands back at the first card, for specs that
  need to see the deal itself.
- **`support/table.ts`** — the table. `snapshot()` reads the whole screen in one
  pass, and `playUntil(predicate)` plays on — taking turns, answering target
  prompts — until it is true. Policies (`alwaysHit`, `stayAfter(n)`) decide what
  the local player does.
- **`support/socket.ts`** — a bare websocket player, for the things a real
  client never does: joining a game already underway, sending a frame that is
  not JSON, a guest trying to start the game.
- **`support/fixtures.ts`** — `app`, `api`, `openPlayer()` for a second browser,
  `openSocket()` for a raw player, and a console guard that fails any test whose
  page logged an error. A spec that causes one on purpose declares it:
  `consoleGuard.allow(/\/api\/rooms\/ZZZZ/)`.

Selectors are `data-testid` and `data-*` attributes rather than text or classes,
so restyling the table does not break the suite. The attributes carry state too
(`data-status`, `data-hand-size`, `data-targetable`), which is what lets
`snapshot()` be one `evaluate` instead of thirty locator round-trips.

## Timing

The server paces the game on purpose — a 5s countdown, a 2.8s title card, 750ms
per card dealt, bots taking about a second to think — and the specs wait it out
rather than trying to skip it. A round takes 20–40 seconds. Specs that play a
whole game are marked `test.slow()`.

Nothing here sleeps for a fixed time and hopes. Waits are on a condition, with
a deadline that reports what the table looked like when it gave up.
