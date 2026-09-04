# Backlog

Status of everything specced so far, and what it takes to finish the rest.

`✅ done` · `🟡 partly` · `⬜ not started`

---

# Shipped

Everything below was specced in an earlier pass and is in the code now. Kept as
a record so the same thing does not get written twice.

| feature | where |
|---|---|
| ✅ Flip 9 | `LobbyRules.FLIP_9` → `RuleSet.flipTarget` / `flipWinsGame` |
| ✅ Bounty | `LobbyRules.BOUNTY`, `Engine.payBounty`, `GameEvent.BountyPaid` |
| ✅ Assassination | `ASSASSINATION`, `GameEvent.BottleSpin`, `SpinningBottle.tsx` |
| ✅ Coin flip (replaced double-or-nothing) | `COIN_FLIP`, `GameEvent.CoinFlip`, `CoinToss.tsx` |
| ✅ Spin the table | `SPIN_TABLE`, `Ctx.rotateHands`, `TableSwirl.tsx` / `SpunHand.tsx` |
| ✅ Don't care + ratio | `DONT_CARE` with `TargetRule.ANY_PLAYER` |
| ✅ Slots | `SLOTS`, `GameEvent.Slots`, `SlotMachine.tsx` |
| ✅ Double it! / Blackjacking / Womp womp | `LobbyRules` + `RuleSet` |
| ✅ Random tension draw (10%) | `dealsTense` in `dealtCards.ts` |
| ✅ Outro shows the table before the scoreboard | `RoundOutro.tsx` |
| ✅ Skip is called "skip", sigil `⏭` | `HEX` |
| ✅ Bust shake is heavier | `.shake-bust` in `index.css` |
| ✅ Current player is clearly lit | `turn-ring`, `turn-vignette`, seat backgrounding |
| ✅ Kick button, config overview, 5s countdown | `Lobby.tsx`, `Countdown.tsx` |
| ✅ Turn timer setting, target score to 1000, card inspector | `LobbyConfig.tsx` |
| ✅ Rules paginate, menus wider on desktop | `RulesPage.tsx`, `.content-width` |
| ✅ Marks — round-long effects that are not cards | `MarkDef`, `Player.marks`, `Ctx.mark`, `MarkSlip.tsx` |
| ✅ Just one more card | `JUST_ONE_MORE` → `NO_FLIP`, `Engine.canFlip` |
| ✅ Unlucky 7 | `UNLUCKY_SEVEN` → `MUST_FLIP`, checked in `roundScore` |
| ✅ Swap (adjustments) — pick any two cards | `SWAP_CARDS`, `PickKind.CARD`, `Ctx.swapCards` |
| ✅ Autostart next round | `GameConfig.autoNextRoundSeconds`, `autoNextRoundAt`, `nextRoundAt` |
| ✅ Seat order follows play order | `useGame.others`, `GameBoard.seatOfId` |
| ✅ Passive cards each have their own ink and seal | `PassiveCardDef.accent`/`seal`, `RoughSeal` |
| ✅ Suicide bomber | `SUICIDE_BOMBER` → `BOMBER` mark, `Ctx.detonate`, `Ctx.raisePrompt` |
| ✅ Anti flip | `LobbyRules.ANTI_FLIP`, two-phase prompt, `GameState.roundAdjustments` |
| ✅ Extreme | `LobbyRules.EXTREME` → `RuleSet.reachesFinished` / `allowsNegative` |
| ✅ Comeback | `COMEBACK`, `PHASE_THROW`, `Ctx.swapScores` |
| ✅ All in | `ALL_IN`, `PHASE_BET`, `HALVED` mark |
| ✅ Many answerers | `PendingAction.responders` / `answers`, `Showdown.tsx` |

---

# Groundwork

## A. Prompts — what the table can be asked

**Landed: the pick domain.** `PendingAction` used to express exactly one thing:
*the drawer picks one target player, and may answer one flat multiple-choice
question.* It now also carries `kind` (`player` / `card`), `validCards` and
`picks`, so a card can ask for cards off the table instead of a seat — which is
what `SWAP_CARDS` uses. Alongside it, a card's effect signature became a `Play`
record rather than a parameter list, so the next thing a card can be asked for
is a field rather than a fourth argument on all fourteen of them.

Also landed with it: `Engine.legalPicks` (an illegal pair is replaced, never
refused, so a bad message cannot strand the table), a random pick on timeout,
a shuffled pick for bots, and card-picking on the client — `canPickCard`,
`.card-pickable` / `.card-picked`, and both cards flying at once through the
generalised `CardFlight` list.

**Landed: many answerers.** Deferred once on purpose — the plan was to do it in
one move with the pick domain, and counting the blast radius first (41 call
sites across 19 files) said that was the wrong order. Built when Comeback
actually needed it, by which point the requirements were known rather than
guessed, and it came to less than the original sketch:

- `PendingAction.responders` and `answers`, with `respondents` spelling out the
  single-responder case so nearly every prompt says nothing and costs nothing.
  `playPendingAction` collects; only a full set resolves.
- **Timeouts** fill in *every* outstanding answer at once rather than one, and
  the clock belongs to the prompt rather than to a player: `deadlineFor` runs it
  for as long as any outstanding responder is human. Reading the drawer alone
  would have left a table of people waiting on no clock at all whenever a bot
  happened to draw the card.
- **Secrecy needed no machinery at all**, which was the real find. The original
  plan was a per-viewer projection of hidden answers. Instead: *answers are
  never sent anywhere while the prompt is open* — not even back to the player
  who gave one. The wire carries who has answered, never what they said, and
  the reveal is the event the resolution emits. There is nothing to leak, so
  there is nothing to filter.

## B. Marks — per-player, per-round status ✅

**Landed.** `Player.marks: Set<String>`, `Ctx.mark` / `Ctx.hasMark`,
`GameEvent.Marked`, cleared in `startGame` and `nextRound`, and drawn as a torn
slip beside the seat (`MarkSlip.tsx`) — deliberately *not* card-shaped, so
nobody reads it as something they can steal or swap. `MarkDef` puts a mark's
face in the catalog the same way a card's is.

Used by *just one more* and *unlucky 7*; *suicide bomber*'s armed state is the
next thing that wants it. `ActionCardDef.skipMarked` keeps a card that would
only re-apply a mark somebody already carries from being spent for nothing —
those seats are not offered, and a card with no seat left fizzles.

---

# House rules

## ✅ Extreme

Two floors lift at once, and they turned out to be separable — `RuleSet` exposes
them as `reachesFinished` and `allowsNegative`, both set by the one rule.

**Cards reach a seat that is already out.** `ActionCardDef.validTargets` reads
the rule set off the state rather than taking it as a parameter — a game knows
what it is being played under, and threading it through every call site would
only be carrying the same answer by hand. Strike, steal, both swaps and unlucky
7 all become real plays against a banked hand, because what they take is points
and those are still on the board. Two guards had to move with it: strike no
longer refuses a hand that is out, and unlucky 7 marks whoever it is pointed at.
Freeze keeps its guard — the rule widens *who* may be aimed at, it does not make
a card that stops a player mean anything against one who already stopped, and a
freeze that undid a bust would be a bug rather than a house rule.

**A round can cost more than it paid.** `enterRoundEnd` clamps the delta at zero
normally and at nothing under extreme, so a banked score can go below it. The
ripples were all fine as they stood: `payBounty` ranks by score, `gameWinner`
takes the maximum, the round winner is still whoever did best. The client needed
one thing — `signedPoints`, because `+-11` is not a number anybody reads.

## ✅ Anti flip

A player who flips out chooses: bank the 15, or take the same off somebody else.
Either/or — spending it means giving it up, so the flip is worth the hand alone
and the victim is down fifteen.

Raised from `endRoundByFlip7`, which is a moment nothing had ever stopped the
table at. It needed no new machinery to hold the round open: `advanceAndCheck`
already refuses to move while a prompt is open, so the scoring simply waits. The
autostart deadline cannot fire underneath it either, and by construction rather
than by a guard — `nextRoundAt` is only set on the PLAYING → ROUND_END
transition, which is exactly what the prompt is holding up.

Three things it did need:

- **Two prompts, not one.** A single prompt carrying both the choice and the
  seats would ask for a seat even from a player about to say "bank it", and
  there is no seat that answer belongs to. So an options-only prompt first
  (targets `[flipper]`, which makes the seat implied and shows only the choice
  picker), then a seats-only prompt if they chose to spend it.
- **Somewhere to put the points.** `GameState.roundAdjustments`, folded into the
  deltas in `enterRoundEnd` and itemised on the summary — a player docked
  fifteen sees why rather than an unexplained zero. Extreme uses the same field,
  and Mutate will.
- **A definition that is not a card.** `ActionCardDef.deckable = false`: it
  ships in the catalog because the client has to draw the prompt, but the rules
  page does not list it among the cards and no deck contains it.

One thing this shook out: the deferred-prompt target filter was too strict. It
kept only seats still *active*, which is right for a bomb but wrong here — after
a flip the whole table is out, and anti flip's victim may even be bust. It now
drops only seats that have left the game, and what to do with the rest is the
effect's own business.

## ✅ Autostart next round

`GameConfig.autoNextRoundSeconds`, a slider in `LobbyConfig` reading "off" at
zero. Server-owned — `Rooms.tick` applies `NEXT_ROUND` when `nextRoundAt`
passes, because a client-side timer would drift between five browsers. The
countdown runs from the scoreboard rather than from the end of the round, so a
bust does not eat most of it before anyone has seen a score; the decision is
`autoNextRoundAt`, kept as a pure function so it can be tested without a room.

Pressing the button early still wins — the timer is a floor, not a gate — and
the round that settled the game never autostarts. Both host and table see the
count. **Still to check** when anti flip lands: it must not fire while that
prompt is open.

---

# Cards

## ✅ Just one more card

The player who draws it can no longer reach flip 7 this round.

Mark `noFlip`, read by `Engine.canFlip`, which both `resolveNumber` and
`anyFlip7` go through. The hand keeps growing past the target with no ceiling
other than the duplicate bust — which is the point, and is worth a lot of
points. Self-targeting; fizzles and redraws for a player who already holds it.
In Chaos and Gambler.

## ✅ Unlucky 7

The player scores nothing this round unless they hit flip 7.

Mark `mustFlip`, checked at the top of `roundScore`. Stacks with `noFlip` into
a guaranteed zero, which is legal and funny — the summary shows the marks a
player was under beside their score, so a bare 0 always has its reason next to
it. In Chaos.

## ✅ Suicide bomber

The card arms its drawer and does nothing else. When they bust — however they
bust — the table stops and asks them who is going with them.

This is the first prompt raised outside a card being played, which is what most
of the work was:

- `Ctx.bust` is the single choke point every bust goes through, so `detonate`
  sitting there covers duplicate, threshold, coin flip, the bottle and a ratio
  without any of them knowing about it.
- `Ctx.raisePrompt` stops the table on a question with no card behind it. The
  card that armed the bomb was spent rounds of play ago, so the prompt mints an
  ephemeral one — never discarded, never reshuffled, so the deck stays honest
  (there is a test for exactly that).
- `PendingAction.phase` / `Play.phase` tell the two halves apart: the card being
  played arms, the prompt it raised later fires. The same field also tells
  `resolvePendingAction` to trust the prompt's own targets rather than asking
  the card's target rule, which describes how it was *drawn*.
- **The responder is a player who is already out**, which nothing else does. The
  clock still runs for them and a timeout still takes somebody.
- **Chains work and terminate**: the mark is spent as it fires, and a bomber
  taken out by a bomb gets a pick of their own. When a bomb goes off while
  another prompt is open — "double it!" spinning the bottle twice — it cannot
  stop the table again, so it picks for itself rather than being lost.

## ✅ Swap (adjustments)

`SWAP_CARDS` — "swap cards", `↔`. Picks two cards off the table, hands and
modifiers alike, and trades them between their owners. Both owners are
re-checked with `resolveBustAfterGain`, which is how it busts people and is the
good part.

Two decisions worth knowing:

- **A card lands in the pile it belongs in.** A modifier that changes owner goes
  to the row, not the hand — otherwise it would count towards the flip and
  collide on its own label. So you can trade a +4 for a 7.
- **The two cards must have different owners.** Two cards changing places inside
  one hand is a hand that has not changed. The client will not offer the pair
  and the server replaces it, rather than refusing and stranding the table.

The existing `SWAP` (whole hands) stays, renamed "swap hands" so the two read
apart. In Chaos. Under "double it!" it swaps back — same as `SWAP` has always
done, since both fire their effect twice on the same two things.

## ✅ Comeback

The player at the bottom of the scoreboard throws against the one at the top;
winning trades the two banked scores outright.

- Drawn by anybody else it is **wasted and replaced** — `Ctx.wasted`, the same
  thing that happens to a card with nobody to hit. Filtering it out of the deck
  for everyone but the trailing player was the alternative, and it would make
  the deck's contents depend on the scoreboard; `Deck.build` is a pure function
  of the config for a reason.
- **A draw is a draw.** Re-throwing would need the table to remember how many
  times it already had, which is state nothing else needs, and "you both threw
  rock" is a fine ending. Noted as an alternative rather than a gap.
- On a tie for last or for first, nobody is *the* player in that spot, so it is
  wasted — the same test the bounty uses, shared as `extremeOfScore`.
- The card **asks nothing when it is drawn**. Whether it does anything at all
  depends on the scoreboard, and asking somebody to throw for a card that is
  about to fizzle would be asking them for nothing. The def describes how it is
  drawn; the prompt it raises describes what is asked.

## ✅ Mutate

Buy a card out of your own score. `PickKind.CATALOG` — a pick made from what the
deck *holds* rather than from what is on the table — and a `Shop` sheet with a
price under every card.

- **Only this table's deck, and only what the buyer can afford.** A friendly
  table cannot buy an assassination that was never in it. The list is priced and
  filtered server-side and re-priced when the answer comes back, so a purchase
  can never put anybody under — extreme or not. A round may cost you more than
  it paid, but not because you spent money you did not have.
- **Number cards and modifiers only.** Buying an action card would mean
  *playing* one, which is a different card and a prompt inside a prompt. A card
  you buy is one you hold. A number costs its value plus five for the privilege
  of choosing it — it is never a duplicate and always the step you needed.
- The card is minted, so buying does not thin the deck everybody else is drawing
  from, and the price is a round adjustment rather than a score that quietly
  moved — it shows on the summary as a line of its own.
- Prices live on the definitions (`price`), so the deck builder can show them
  too.

---

# Deck builder ✅

Players can build their own deck: a stepper per card, a running total, and the
same rules the server keeps.

- **The bug was real.** `deckPresetId` keys the preset list, and every lookup
  fell back to `catalog.decks[0]` — so a table playing its own deck would have
  been described everywhere by a deck it was not playing, down to the card list.
  `findDeck` now returns undefined for a deck it has no entry for, and every
  caller says "a deck of your own" instead. `TableNote` already did this, with a
  comment explaining why; it was right.
- **The floor on number cards is termination, not taste.** An action card nobody
  can be hit with fizzles and deals its drawer another, and a deck made mostly
  of those can keep doing that for as long as the discard pile keeps being
  shuffled back in. So: at least 12 numbers, and numbers at no less than 40% of
  the whole.
- `sanitizeDeck` trims rather than refuses wherever it can — a count typed too
  high is clamped, a card this build has never heard of is dropped — because
  either is likelier to be an old config than an attack. What it will not do is
  hand back a deck a table could hang on, and a config it cannot save falls back
  to a preset.
- A house rule's prompt has a face and a name but is not a card, so
  `deckable = false` keeps it out of the builder and out of any deck.
- The limits ship in the catalog rather than being restated on the client, so
  the builder says exactly what the server will accept.
- `localStorage` for the deck you built, base64 for the deck you send somebody.
  The pure deck logic lives in `game/deck.ts` rather than in the component —
  which the fast-refresh lint rule insisted on, and was right about.

---

# UI

## ✅ Seat order is not play order

The seats around the table did not follow the turn order.

- `useGame.ts`: `others = players.filter(p => p.id !== localPlayerId)` keeps the
  array order, so seats fill from player 0 rather than from the seat after
  mine. With `[A,B,C,D]` and me as C, the table shows A, B, D where play goes
  D, A, B.
- Fix: rotate by `meIdx` — `[...players.slice(meIdx + 1), ...players.slice(0, meIdx)]`.
- Two places have to agree, not one: `others` drives which seat a player is
  *rendered* at, and `GameBoard.seatOf` works the seat out again from the raw
  player index for anything that flies between seats (steals, played cards,
  `angleTo`). Best fixed by having `seatOf` read the rotated list rather than
  repeating the skip-me walk.
- Then check `SEAT_POSITIONS` reads in the same direction as play: it is
  left → top-left → top-right → right, so starting from the bottom seat it runs
  clockwise. `Ctx.rotateHands` and `TableSpun` use seat order too, so the spin
  animation is only correct once these agree.
- Worth an e2e assertion: with four seats, the seat marked "next" is the one
  that gets the turn.

## ✅ Passive card identity

`PassiveCardDef` gained an `accent` and a `seal` shape, both shipped in the
catalog beside the sigil — the client owns no rules, so everything it needs to
draw a face comes down the wire. `RoughSeal` draws circle / hexagon / shield /
scallop, struck twice slightly out of register the way a real stamp lands.

Second life is a rose scallop, armor a slate shield, ×2 an ochre token. The
`+n` family deliberately keeps one colour and one shape: five colours would
break up the one group on the table that should read as a group, so what tells
a +2 from a +10 across the felt is stroke weight off `bonusPoints`.

---

# Sequencing

1. ~~**Marks (B)**~~ ✅
2. ~~**Just one more card**, **Unlucky 7**~~ ✅
3. ~~**Seat order**, **autostart**, **passive identity**~~ ✅
4. ~~**Card picking (A)**, landed with `swapCards` as its first user~~ ✅
5. ~~**Suicide bomber**, **Anti flip**~~ ✅
6. ~~**Extreme**~~ ✅
7. ~~**Comeback**, **All in**~~ ✅ — with the multi-answerer work at the top of
   the step, where it belonged.
8. ~~**Mutate**, then **deck builder**~~ ✅ — pricing landed once and both use it.

Everything specced is built.

# Watch out for

**Stack the deck; don't hunt for a seed.** A seed pins a shuffle, so a spec that
wants a particular card has to search for a seed that happens to deal one — and
that stops being true the moment the deck's contents change. It happened three
times in one afternoon of adding cards to Chaos, each time failing with a
message about the game rather than about the deck.

`hostStacked` says it outright: `['2', '3', '4', '5', 'freeze']` puts those
cards on top of the deck in that order, so the four opening cards are known and
the host's first draw is a freeze. The named cards are lifted out of the
shuffled deck rather than added to it, so the deck is still the deck and every
card-conservation check holds. Gated behind the same test hooks as the seed.

Seeds are still there for the rounds whose *shape* is the point — a bust, a flip
— where no particular card matters.

**The e2e harness has to be able to answer every prompt.** It could only click
seats, so a card that asked a question was left to the server's turn clock —
slow, but it worked. A card-picking prompt would have done the same, quietly
turning every Chaos-deck spec into a 30-second wait. `answerPrompt` now handles
all three: cards, seats, then options.

**A pick is not committed until it is complete.** The board marked the prompt
answered on the first click, which is right for a seat and wrong for two cards —
the picker went dead half way through. `data-chosen` now means the answer is on
its way, and nothing else.

**The suite was slow because the game is slow.** Almost none of the e2e time was
code — it was a table taking its deliberate time: a title card, a card dealt at
a time, bots thinking, a closing card. `LETITRIDE_PACE` scales every beat the
server keeps and, through the catalog, every beat the client keeps, together, so
the sequencing is untouched and only the waiting shrinks. The suite runs at 0.25
and went from 2.9 minutes to 1.5. Gated behind the test hooks, and it only ever
speeds a table up.

Two specs failed when the pace changed, and both were the spec's own fault: one
let the harness take a turn it meant only to watch, the other waited for a bust
that four players are not guaranteed to produce. Both are seeded or scoped
properly now, and the suite has run clean twice over.

**A fanned hand is unclickable.** Cards overlap by design — it reads well — but
every card except the last is half covered by the next one along, so a prompt
that asks for a card could only reliably be answered with the top one. A hand
being picked from is now laid out flat. Playwright found it ("subtree intercepts
pointer events"), but it was never only a test problem.

**Nothing on a click target may animate forever.** The pickable cards started
out gently bobbing, which Playwright refuses to click ("element is not stable")
and which is genuinely harder to aim at. They are ringed rather than lifted now,
which also sidesteps a second problem: every card carries an inline transform
for its place in the fan, and a class transform cannot override one.

---

# Open questions

- ~~**Anti flip vs. extreme**~~: settled — the deduction stops at zero with
  extreme off and goes through it with the rule on. That is the whole of what
  extreme changes about scoring.
- **Comeback**: fizzle-and-redraw when the wrong player draws it, or filter the
  deck? Assumed fizzle.
- ~~**All in** with two bettors~~: settled — it is wasted rather than played.
- **Comeback on a draw** does nothing. Re-throwing is the alternative and would
  want somewhere to keep the count; worth revisiting if a draw feels flat in
  play rather than on paper.
- **Just one more** is self-targeting as built, on the reading that the card
  tempts its own drawer. The other reading — hand it to the leader to deny them
  the flip bonus — would make it `ANY_ACTIVE` and a one-line change.
- **Mutate prices** are a first guess — a number card at value + 5, a freeze at
  20, an assassination at 40. They want one pass of real numbers after a few
  games, and they are all in one place (`price` on each definition) for it.
- **The Chaos deck has grown a lot** — nine cards added across this work. It is
  meant to be mayhem, but the action share is worth a look in play.
- **Bots** get random legal picks for every new prompt. Fine for swap and
  comeback; for All in a random bet is close enough to a real one. Mutate is
  the one where random reads as broken — a bot buying a `+2` for 5 points is
  fine, a bot buying nothing at all is better than a bot buying an
  assassination it cannot use.
