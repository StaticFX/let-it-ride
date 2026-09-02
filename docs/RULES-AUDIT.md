# Rules audit

Checked the implementation against the published **Flip 7** rules and against
our own additions in [`features/`](../features). Everything below was verified
against the Kotlin engine, and every fix has a test in
`backend/src/test/kotlin/com/letitride/engine/`.

---

## 1. Bugs found and fixed

### Cards were quietly disappearing from the game

Anything knocked out of play — a card removed by **strike**, the duplicate a
**second life** ate, a **spent armour**, or the action card itself once it
resolved — was dropped on the floor rather than put on the discard pile. The
deck therefore shrank permanently: a long game slowly ran out of cards and could
not recycle its way back.

Now every removal goes through the discard pile. `FullGameTest` plays complete
games with every deck preset and every house rule and asserts, **after every
single transition**, that the exact same set of card ids still exists somewhere.

### Running the deck dry froze the game

`HIT` on an empty deck returned the state unchanged, so the turn never advanced
and the table locked up. The deck now reshuffles the discard pile back in; if
there is genuinely nothing left anywhere, the player goes out instead of
stalling.

### Flip 7 only counted on a voluntary hit

Reaching seven unique numbers during a **draw 3**, during the opening deal, or
via the *double draw* house rule did nothing — the round carried on. Every draw
now goes through one code path, so the check fires wherever the seventh card
comes from. Steal and swap can also complete a Flip 7 and are checked too.

### Second chance could be hoarded

Flip 7 says a player may hold only one Second Chance: draw a second and you must
give it to a player who has none, or discard it if everyone already has one.
Neither happened. Now implemented, with the pass-on emitted as its own event.

### Second chance rescued a bust it should not

With *blackjacking* enabled, going over 21 was being cancelled by a second
chance. Second Chance covers duplicates only.

### ×2 scoring order was accidental

The old code added the hand value a second time and then added the flat
bonuses, which happens to give the right answer for a single ×2 but expressed no
intent. Scoring is now explicit and tested:

```
number cards → apply every ×2 → add flat modifiers → add the Flip 7 bonus
```

So `10 + 5` with a ×2 and a +10 scores **40**, not 50, and the Flip 7 bonus is
never doubled.

### "First to N points" crowned the wrong player

It picked the first player *in seat order* at or above the target. When several
players cross the line in the same round, the highest score wins.

### The turn order could deadlock

If every remaining active player was skipping (**hex**), the loop cleared all
the skip flags and returned with the turn still parked on a player who was
already out — nobody could act again. There is now a fallback to the first
active player.

### Action cards were unvalidated

`PLAY_ACTION` never checked that the sender was the player who drew the card, or
that the card id matched the pending one. Any client could play anyone's action
card, as any card. Both are validated now — and since the whole engine moved
server-side, the client cannot fabricate one at all.

### Action cards could be aimed at players who were already out

Freezing a busted player, striking someone who had gone out, and so on. The
target must still be in the round; otherwise the card falls back onto the player
who drew it.

### Slots cancelled a draw 3 in progress

Drawing **slots** during forced draws overwrote the running forced-draw counter
instead of stacking on top of it, silently eating the rest of the draw 3. It now
stacks, the same way a nested draw 3 does.

### Double draw skipped card effects

The *double draw* house rule pulled a raw card straight out of the deck, so an
action card drawn as the second card of a turn was silently binned instead of
being played. It now goes through the normal draw path.

### Double or nothing inflated the deck

The ×2 it awards was minted as a brand-new card that then joined the discard
pile at round end — every payout permanently added a card to the deck. Minted
cards are now marked ephemeral: they score while they are on the table and are
dropped when the round ends.

### Stealing a duplicate did not bust you

**Steal** could hand you a number you already held with no consequence. It now
runs the same bust check as a draw (and a second life can still save you).

### The "must draw" rule was inconsistent

It only applied in round 1, and a player whose opening card was a modifier could
go out with an empty hand from round 2 onward. Now uniform: you cannot go out
before you have taken something, unless the host enables *no forced draw*.

### The opening deal could deal twice

If a player's opening card was a modifier their hand stayed empty, and the deal
loop came back around and gave them a second card.

### Deck card counts were wrong

They were hardcoded next to each preset and had drifted: *chaos* claimed 126
cards but built 125, *gambler* claimed 104 and built 105, *friendly* claimed 101
and built 105. Counts are now derived from the deck, with a test that the
advertised number matches what is actually built.

### House rules existed but could not be turned on

`LOBBY_RULES` was defined in the engine and rendered on the rules page, but the
lobby config never put anything in `rules` — so every house rule was dead code.
They are now selectable in the lobby.

### The turn timer was never implemented

`turnTimeSeconds` was configurable and had a slider, but nothing counted down.
The server now runs the clock (see below).

---

## 2. Deliberate differences from Flip 7

These are ours on purpose, not mistakes:

| | Flip 7 | Here |
|---|---|---|
| Number cards | 0–12 | 0–13 in the house decks — the `flip7` preset is exact |
| Round result | everyone just banks | we also highlight a round winner, for the summary screen |
| Card pool | freeze, flip three, second chance, modifiers | plus strike, steal, hex, swap, armour, bounty, double or nothing, slots |

A faithful **Flip 7** deck preset was added: 79 number cards (0 once, then N
copies of N up to 12), 3× freeze, 3× flip three, 3× second chance, and one each
of +2, +4, +6, +8, +10 and ×2 — **94 cards**, verified by test. The modifier set
was previously incomplete (only +4 and +10 existed); +2, +6 and +8 were added.

The default win condition is now **first to 200**, which is Flip 7's own.

### One genuine ambiguity

When a player turns up an action card *during* a flip three, the published rules
are read both ways: resolve it immediately, or finish the three cards first. We
resolve it immediately and resume the outer draws afterwards — the pending
draws are pushed onto a stack, so a flip three inside a flip three inside a flip
three all unwind in the right order. If you prefer the other reading, it is one
branch in `Engine.processOneForcedDraw`.

---

## 3. Our additions, tracked against `features/`

**`features/cards.md`**

- **Double or nothing** — implemented. 50/50 on the server's seeded RNG: either
  you gain a ×2 for the round or you bust out of it.
- **Slots** — implemented as a self-targeting card that pulls one extra card,
  with the slot machine overlay.
- **All in** — *not implemented.* It needs a simultaneous hidden-selection phase
  (every player commits a card face down before anything resolves), which is a
  new game phase rather than a card. It is the only thing in `features/` that is
  still missing.

**`features/lobby.md`**

- **Blackjacking** — implemented (bust above 21).
- **Double it!** — implemented (every action card effect fires twice).
- **Womp womp** — implemented (action cards redirect onto whoever drew them,
  and modifiers you draw are handed to a random other player).

**`features/visuals.md`**

- Turn timer, default 30s — implemented, and it behaves as specified: when it
  runs out you go out, and if you were sitting on an action card a random player
  gets hit with it. The clock is authoritative on the server, so an idle or
  disconnected player cannot hold up the table.
- Target score max 1000, default 200 — implemented.
- Kick button, 5 second countdown, deck inspection in the lobby — already
  present, kept.
- The rules page was split from 3 pages to 4 (basics / scoring / cards / house
  rules), since the scoring rules are now worth spelling out.

---

## 4. Why the server holds the rules now

The old build ran the engine in every browser with one player acting as host.
That made several of the bugs above unfixable in principle rather than in
practice: the deck was in every client's memory (so the next card was visible to
anyone reading the socket), and any peer could send arbitrary state.

The engine is now Kotlin, in one process, and clients only send intents —
`HIT`, `STAY`, `PLAY_ACTION`. The deck is never serialised to a client; only a
count is. Card animations are driven by the events the server emits for the
transition, so the player still sees the card fly out of the pile without the
client ever knowing what is underneath it.

Pacing that the client used to drive — the opening deal, forced draws, bot
moves — is now on the server's own clock, so a slow, stalled or hostile client
cannot freeze or rush the table.
