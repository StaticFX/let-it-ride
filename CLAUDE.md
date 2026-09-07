# Working in this repo

A Flip 7-style push-your-luck card game. A Kotlin/Ktor backend holds the rules and
serves the React frontend out of the same jar, so the whole thing deploys as one
container on one port. Rooms live in memory; there is no database.

`README.md` is the tour. This file is what to know before changing anything.

## Commands

```sh
./gradlew :backend:test                # the engine — 253 tests, ~30s
./gradlew :backend:run                 # rules on :8080
./gradlew :backend:runDev              # ...with the testing mode on
bun --cwd frontend run dev             # UI on :5173, proxying /api and /ws
bun --cwd frontend run typecheck
bun --cwd frontend run lint
cd e2e && bun run test                 # builds the jar, serves it, drives Chromium
cd e2e && E2E_SKIP_BUILD=1 bun run test   # reuse the jar you already built
cd e2e && bun run test tests/lobby.spec.ts        # one file
./gradlew :backend:test --tests "*.BustTest"      # one class

./gradlew :backend:buildFrontend :backend:buildFatJar   # the artefact we ship
```

CI runs the same set in three jobs — backend tests, frontend typecheck/lint/build,
and the e2e suite against the packaged jar. Run what your change touches before
saying it works, and the e2e suite before saying the client does.

There is no Kotlin linter — `kotlin.code.style=official`, four spaces, and match
the file you are in.

## The rules that matter

**The server owns every rule.** Clients send intents (`HIT`, `STAY`,
`PLAY_ACTION`) and render the state and events they get back. Nothing in
`frontend/` decides anything about the game — if you find yourself computing a
score, a legal target or a bust in the client, the answer belongs on the server
and needs to come down the wire.

**The deck is never sent to a client.** Only its size. The flying-card animation
is driven by `GameEvent.Draw`, which is what lets the table show the card that was
actually drawn without revealing what is behind it. The one exception is
`GameStateView.devDeck`, which is null unless the server was started with the test
hooks on.

**One state per viewer.** `Room.broadcast` encodes the view once per connection,
not once for the room, because rolling rules gives each player a hidden hand and
which faces you may see is a fact about *you*. `Player.gamblers` is `@Transient`
so it cannot ride out on the player list at all; your own come down in
`GameStateView.myGamblers` and everybody else's as a count. `Room.redactFor` is
the one place an *event* is cut down for who is reading it, and
`EventRedactionTest` fails the build if a new event carries a card without
somebody having said who may see it. A leak here has no symptom, which is why
there are three guards and not one.

**`engine/` is pure.** No I/O, no coroutines, no clock, no `Math.random`. One
entry point — `Engine.transition(state, action, rng)` — returning a new state and
the events it produced. Card effects talk to the game only through `Ctx`, and
every command on `Ctx` emits an event so the client can replay the transition as
animation. Pacing, sockets and bots live in `server/Rooms.kt`, on the other side
of that line.

**Cards are conserved.** A card is moved between the deck, a hand, the modifier
row and the discard pile — never created or destroyed. The engine suite asserts
after every transition that no card appeared or vanished and that no surviving
hand holds a duplicate (`GameState.allCardIds()` in `TestSupport.kt`). If you need
a card that was never dealt, mint it with a `tmp-` id: `Card.isEphemeral` keeps it
out of the discard pile and drops it at the end of the round, so the deck stays
honest. Anything else that changes a pile has to put the card somewhere.

**Every shuffle goes through `Rng(seed)`.** A room is replayable from its seed
alone, which is what the e2e suite's determinism rests on. Never reach for
`Random.Default` or `Math.random()` in engine or room code.

**Everything is a card.** Rule one. There is no second mechanism for "you are
under this until the round ends" — the flip you cannot take, the round that
scores nothing, the bomb you are carrying are all `PassiveCardDef`s lying in the
modifier row, which is what makes every one of them stealable, swappable and
worth pushing onto somebody else. They are minted with a `tmp-` id rather than
dealt, and `deckable = false` keeps them out of every deck. Do not add a flag on
`Player`; add a card.

**A card that is its own animation announces itself and settles later.** A coin
turning over and a bottle slowing down are not decoration on a result — they
*are* the card, and resolving one in the same breath as announcing it hands the
client the answer and the question together, so the seat reads "bust!" while the
coin is still in the air. Those cards emit their event, call `Ctx.land`, and the
outcome arrives in a batch of its own once the table has finished watching —
`PendingOutcome`, stepped by the room off the same animation gate as everything
else. The slot machine has always worked this way; anything you add that is
worth watching should too.

**The client times animations, the server waits.** When a batch of events goes out
mid-round the room opens an animation gate and refuses to move until the owning
client sends `ANIM_DONE` — or until `ANIMATION_GATE_MAX_MS` (7s) passes, so a hung
tab cannot own a table. Durations live in `useGame.ts` (`ANIMATION_TTL_MS`) and
nowhere else; the server never guesses how long a bust takes. Two ceilings apply
to anything you add there, and both are documented next to the table: the gate's
7s — an animation held back behind a played card spends `SMASH_LAND_MS` of it
before it starts — and the closing window a round-ending animation gets before
the card covers the table, which is `outroPreambleFor` in `Rooms.kt` and is the
one place the server has to be told roughly how long something takes. Lengthen
an animation and the number there has to follow it.

**Animate for reading, not for recognising.** Every duration in that table was
written for cards the table already knows: a freeze is 1800ms because you know
what a freeze is and it only has to say *who*. A card nobody has seen has to say
who, what, and what that even means — a name and a sentence, read by several
people at once — which is nearer four seconds. That is what
`GAMBLER_REVEAL_FIRST_MS` is, why the gate's ceiling moved to make room for it,
and why the reveal is drawn on a sheet of its own at a size the description
actually renders at. The gate is a backstop against a hung tab, not a design
budget; `closeGate` hands animating time straight back to whoever is on the
clock, so a long read never costs anybody their turn. Whether a card is new is
the *table's* fact, not a browser's — the player of a card owns its gate and
already knows what it does, so `Room.markFirstSight` decides and the client only
picks a length.

**Test hooks gate anything that reveals or chooses cards.** `LETITRIDE_TEST_HOOKS=1`
turns on the pinnable seed, the stacked deck, the pacing knob and the testing
panel. Everything behind it must be inert without it — the room drops the message,
the field is not serialised, the panel is not rendered. CI checks the published
image reports `"testHooks":false` from both `/api/health` and `/api/catalog`.

## Where things live

```
backend/src/main/kotlin/com/letitride/
  engine/      the whole rulebook, pure
    Model.kt        cards, players, state, actions
    Engine.kt       the reducer, plus the Ctx card effects act through
    CardDefs.kt     what each action and modifier card does, and the Catalog
    GamblerCards.kt the hidden hand's cards — rolling rules
    DeckPresets.kt  the presets, DeckLimits and sanitizeDeck
    LobbyRules.kt   house rules as data; RuleSet is what the engine reads
    Events.kt       everything a transition can announce
  server/
    Rooms.kt        in-memory rooms, the pacing clock, the animation gate, bots
    Dto.kt          the wire types; GameState.toView projects one player's view
    DevMode.kt      the local testing mode — inert without the hooks
frontend/src/
  game/types.ts     mirrors the wire types; decides nothing
  net/client.ts     REST + WebSocket, with reconnect
  state/gameStore.ts a mirror of server state, nothing more
  hooks/useGame.ts  events → animations, and the ack that releases the table
  components/dev/   the testing panel
e2e/                Playwright, driven through the DOM like a player
```

## Recipes

**A new action or modifier card** — define it in `CardDefs.kt`, register it in
`Catalog`, put copies in whichever `DeckPresets` should hold it, and test it. The
client needs nothing: faces, names, descriptions, sigils, colours and prices all
come down from `/api/catalog`, and hardcoding any of them in the frontend is a bug.
A card that stops the table raises a `PendingAction`; one that can do nothing
should `fizzle` and deal its drawer a replacement rather than parking the round.

**Anything that changes what a round *pays*** — a doubling, a tithe, a debt —
goes in `Engine.settleGamblerEffects`, which takes a delta map and returns one,
the same shape `payBounty` has. None of it belongs in `roundScore`, which is
about the cards in front of somebody. Note that `enterRoundEnd` builds its
result from `ctx.state` and not from the snapshot it read at the top: that
function *moves cards* as well as computing numbers, and copying the old
snapshot over the top put a settled loan's IOU straight back in its tray.

**Anything between rounds** — the shop is `phase = ROUND_END` plus a
`GameState.interlude`, deliberately *not* a fifth `GamePhase`. A new value in
that enum is the one wire change that cannot degrade: a tab that has not
reloaded falls through `App.tsx`'s switch to the lobby. `Room.tick` dispatches
it **above** the non-`PLAYING` teardown, because the window keeps a clock and an
animation gate of its own and those three lines drop both. Its stock is
**minted** — the dealer's cards are not the table's deck — and a minted gambler
card is the one exception to `tmp-` meaning "gone at round end", because
`nextRound` sweeps the hand and the modifier row and never the gambler zone.

**A card that answers a card** — give it `PlayWindow.IN_RESPONSE` and a
`counters` predicate saying which frames it may answer. That predicate is what
keeps the window *silent*: the server reads every hand, so a table where nobody
could answer never pauses at all. When one does open it asks **everybody** still
in, not just the holders — otherwise "waiting on one more" plus a seat that
always gets asked names whoever is carrying the nullify. Only the top frame is
ever waiting; a frame underneath one has had its window. The counter acts
through `Ctx.cancelAnsweredFrame` / `retargetAnsweredFrame` / `copyAnsweredFrame`
— its own frame is off the stack by the time its effect runs, so "the card it
answered" is simply whatever is on top now. `GameState.isInterrupted` covers the
stack, so any path that leaves a frame on it hangs the table.

**A new gambler card** — rolling rules. Define it in `GamblerCards.kt`, add it to
`GamblerCatalog.all`, and put copies in `DeckPresets.ROLLING_RULES`. It carries
the same `onPlay` as an action card, so every `Ctx` primitive works untouched;
what is different is that its holder chooses the moment. Say which of the six
`PlayWindow`s that is and `Engine.windowOpen` does the rest — the client is
*told* what is playable in `GameStateView.playableGamblers` and never works it
out. A card with nothing to point at is simply not offered rather than fizzling:
a card off the deck was never chosen, so replacing it is fair, but spending one
out of your own hand on nothing is a decision you did not make. A card whose
promise is about your *next* draw arms an effect card in the modifier row and is
spent by `resolveDrawnCard` — see `redirect`, which is the pattern.

**A new house rule** — add a `LobbyRule` in `LobbyRules.kt` (plain data, so a
config is a list of ids) and read it through `RuleSet` in the engine. The rule's
behaviour lives in the engine, never in the rule object. A rule a *mode* always
brings with it goes in `forcedRulesFor`, which both `Room.sanitize` and
`RuleSet.of` apply — the first so the lobby shows it on, the second so a state
that never went through a room is still right.

**A new event** — add it to `Events.kt`, mirror it in `types.ts`, and handle it in
the `switch` in `useGame.ts`. An event nobody animates is still worth emitting;
the client simply acks it.

**A new field on the wire** — optional on the client, always. `types.ts` is full of
"older servers omit it" for a reason: a tab that has not reloaded is talking to
the server you just deployed.

**Anything a client should not be able to do** — put it behind `testHooksEnabled()`
and add the negative test. `DevModeTest` is the pattern: a room without the flag
ignores the message entirely.

## Frontend conventions

- **Tailwind classes, not inline styles.** Colours come from the CSS variables in
  `index.css` (`var(--ink)`, `var(--felt)`, `var(--accent)`, …). Reusable patterns
  become components; the hand-drawn look is `RoughShapes`, `SketchButton`,
  `PlayingCard`, `CardBack` and the `.sketch-box` family, not new one-off CSS.
- **UI copy is lowercase and conversational** — "let it ride!", "waiting for host
  to start…", "a deck of your own". Match it.
- **`data-testid` on anything the e2e suite touches**, and keep the id stable when
  the label changes; the label is decoration.
- **The lint is strict, and two rules bite.** `react-hooks` rejects `setState`
  inside an effect — use a ref (see the bot-filling loops in `Lobby.tsx` and
  `DevPanel.tsx`) or a keyed uncontrolled input. The React Compiler rejects a
  `useMemo` whose declared deps are narrower than what it infers — hoist
  `state?.config.deck` into a local and depend on that.
- The store is a mirror. Anything derived belongs in a hook or a selector, not in
  a second copy of the state.

## Testing expectations

- Engine changes come with engine tests. The suite plays complete games under
  every preset, every house rule and 25 shuffles; if your change can be reached by
  playing, it will be.
- **Prefer a stacked deck to a seed.** `app.hostStacked` names the cards it wants,
  so it goes on meaning that when the deck's contents change. A seed only means
  what it means by accident — the two left in `seeds.ts` are the rounds whose
  *shape* is the point.
- The e2e suite drives the DOM the way a player does and waits out everything the
  server paces. It runs with `LETITRIDE_PACE=0.25`; a spec that only passes at one
  pace is a broken spec.
- For anything fiddly to reach by playing, `./gradlew :backend:runDev` and the
  testing panel (`` ` `` at the table) will write the situation down for you — a
  hand one off the flip, a duplicate on top of the deck, everybody on match point.

## Style

The comments in this codebase explain *why*, at length, especially where the
obvious implementation was tried and was wrong. That is deliberate — keep it up in
code you add, and do not strip it from code you touch. Test names are sentences
about behaviour ("a stacked card is moved rather than conjured"), not labels.
Prose in the same voice throughout: plain, unhurried, stating rather than selling.
