# Rolling rules: a hand nobody can see

> In 2.0 there is a feature list of a whole new game mode.

**The first slice of it is in.** `2.0/features.md` describes four things — a
hidden second hand, cards that answer cards, a shop, and an auction — and this is
the first: you keep a hand nobody else can see, you find its cards in the deck,
and you choose when to spend them. The other three are designed and are not
built.

## What is actually there

A **mode**, chosen in the lobby above the deck, because it is not a house rule:
it changes the screens you see rather than a number the engine reads. It brings
its own deck and it always underlays *extreme* — sent along with the mode rather
than assumed, so the lobby shows the game you are about to play instead of
correcting it in round one.

A **third zone on a player**, beside the hand and the modifier row. It survives
the round, which the other two do not, and no ordinary card can reach it —
`stealRandom`, `swapCards`, `swapHands` and the spin all read `hand + passives`
and nothing else, so "your second hand is your protected good" cost no code at
all. `nextRound` deliberately does not clear it, and there is a comment there
saying so, because tidying that up to match the two piles either side would
empty everybody's tray between every round.

**Six cards**, chosen to exercise the whole machine rather than to be the six
best: redirect, shuffle, draw 2, second opinion, cheating, fuck it. Between them
they cover on-turn, always and on-out; an effect card left in the modifier row; a
prompt raised long after the card was played; an outcome watched before it is
felt; points spent; the deck stirred; and a draw intercepted before it lands.

## One state per viewer

This is the part that was actually hard. The room used to encode the state once
and send the same bytes to everybody, and the game's only secrecy mechanism was
"do not transmit it at all". Neither works for a hand that is face up to one
person and face down to four.

So `broadcast` encodes once per connection. That is a real cost — five seats is
five passes over the same few kilobytes — and it is what a hidden hand costs:
which faces you may see is a fact about *you*, and there is no honest way to
answer it once for everybody. The alternative, a shared payload with a private
patch bolted on, needs the redaction written anyway and then adds a merge on top.

Three guards, because a leak has no symptom:

- `Player.gamblers` is `@Transient`, so a hand cannot ride out on the player list
  even if every other rule here is forgotten;
- `Room.redactFor` is the one place an *event* is cut down for its reader, and
  `EventRedactionTest` walks the sealed hierarchy and fails the build when an
  event carries a card and nobody has said who may see it. It has already caught
  two — `gamblerPlayed` and `redirected` — which is the whole point of it;
- an end-to-end spec runs two browsers and asserts the other one's page contains
  no gambler card at all, only a number.

## Drawing one, and what that costs

Gambler cards off the deck are **real cards off the same deck** — `g-` ids,
conserved like everything else, discarded when they are spent and shuffled back
in. Fifteen of them in a deck of a hundred and twenty-odd, which is about one
draw in eight: thin on purpose, because drawing one has to feel like finding
something. *(The shop's stock is a different matter, and I had it wrong when
this was first written — see "the money is the score" below.)*

Drawing one does not cost you your draw — you take another card, and another,
until you turn over an ordinary one. There is a depth guard on that, because
`drawRaw` folds the discard pile back in when the deck runs dry and a table whose
remaining cards were all gambler cards would otherwise draw for ever.

A card with nowhere to go — a sixth, against a cap of five — goes to the discard
pile rather than being refused. A card left on top of the deck is a card the next
player draws, and the one after that.

## Playing one is two steps

Clicking a card in the tray sends `PLAY_GAMBLER { cardId }` and nothing else — no
target. If the card wants a seat, a question or a handful of cards, the server
comes back with an ordinary prompt, and every picker the client already has knows
how to answer it. Zero new picking code, and the server stays the only thing that
decides what a legal target is.

It is latched rather than sent on the click, for the reason every other pick in
this game is: the server drops a move made while the table is animating, and the
batch that makes you want to play a card arrives *with* the animation of it.

A card played out of turn does not move the turn. `afterGambler` checks the flip
and can end a round — a card played by a seat that is already out may be the last
thing that settles it — but it does not advance, because answering a question is
not taking a turn.

**The bug worth writing down**: `runGambler` used to discard the played card
before running its effect, the way `runAction` does. "Cheating" reaches into the
deck *and the discard pile* for a gambler card — so it found itself, sitting on
top of the pile where it had just been put, and handed itself straight back for a
hundred points and no cards used. A gambler card is spent after its effect now,
which is also simply what "spent" means.

## Long enough to read

Nobody knows these cards. Every animation budget in this game was written for
cards the table already recognises — a freeze is 1800ms because you know what a
freeze is and the animation only has to tell you *who*.

So a gambler card the table has not seen is held for **4200ms**, on a sheet of
its own in the middle of the felt, at a size where the description actually
renders. The fifth one of the evening gets 1600ms. Which of those applies is the
*table's* answer and not a browser's: whoever played the card owns the animation
gate, and they are the one person who certainly knows what it does — a client
that only slowed down for cards it personally had not seen would let them wave it
past everybody else. So the room keeps `seenGamblers`, the event carries
`firstSeen`, and the client turns a fact into a duration.

Two numbers moved to make room. `ANIMATION_GATE_MAX_MS` went from 5s to 7s: it is
a backstop against a hung tab, not a design budget, and `closeGate` already hands
animating time straight back to whoever is on the clock, so a long read never
costs anybody their turn. And `OUTRO_AFTER_GAMBLER_MS` is 4800ms, because
"fuck it" and a revive can end a round and a round-ending animation is not gated
at all.

The first attempt put the card on the felt at `h * 0.44`, where it landed on top
of the draw pile and the discard pile with its caption printed across both. A
card somebody is being asked to read for the first time cannot be printed over
two other things, so it takes the middle of the table on a sheet, the way a
showdown does.

## Cards that answer cards

The second slice. Play a gambler card and, if anybody at the table is holding
something that could stop it, the table stops and asks — and a counter can
itself be countered, so the pile in the middle gets taller and settles from the
top down.

**The window is silent when it can be.** The server reads every hand, so it
knows whether anybody *could* answer; a table where nobody is holding a counter
never pauses at all, and nobody learns that anybody was asked. Once it does open
it asks **everybody** still in, not only the holders. That second half is not
politeness: "waiting on one more" plus a seat that always gets asked would name
whoever is carrying the nullify, and the hidden hand would leak straight back
out through the prompt. There is a test that runs the same window twice with the
counter in two different hands and asserts the two are indistinguishable.

The residual leak — that a window opening at all means *somebody* can answer —
is real and deliberate. Closing it costs a pause on every gambler card ever
played.

**Aimed before it is asked about.** A counter reads who a card is pointed at, so
a card that needs a target is targeted *first* and only then goes on the stack.
That reordered the play path: `playGambler` no longer resolves anything, it
pushes a frame, and the prompt that picks a seat pushes one too.

**Three counters at first, and the fourth once there was something to turn.**
Nullify, nahhh and copycat shipped together; deflect was held back through M2
because every gambler card written by then resolved on whoever played it and no
effect among them read its target at all, so re-pointing a frame changed
precisely nothing. `Ctx.retargetAnsweredFrame` went in and waited. The card
followed the first one that actually attacks a named seat.

What gives nullify something to do meanwhile is a nice accident of the design: a
*counter* is aimed at the player whose card it stops, so answering somebody's
answer is exactly the case the card describes. The three-deep spec is a shuffle,
a nahhh that stops it, and a nullify that stops the nahhh.

**The stack holds action cards too, and it had to.** Nullify says "stop a card
aimed at you" and deflect says "a card somebody uses on you goes back at them" —
*card*, not gambler card, and the spec gives redirect as an *example* rather than
the whole set. A freeze pointed at your seat is exactly what both of them
describe, and for a while neither could touch one: the stack was gambler cards
only, so the two cards in the set that say "card" could answer a third of the
cards in the game.

`StackFrame` gained a `kind`, and `resolvePendingAction` gained one detour. It is
taken at the point where everything about the play is already settled — who, at
whom, with what — which is the first moment a counter can be asked a question it
can answer, and the last moment before the card goes off. If nobody at the table
is holding anything that could answer, the detour is not taken and the path is
the one it always was; every classic game takes that arm, and the reordering
costs it nothing.

Two of the four stay gambler-only, and their own text is why. Nahhh answers
"when another player wants to *play* a gamblers card" — a freeze somebody drew
and was made to aim is not a thing anybody chose. Copycat mints its copy as a
gambler card and runs it as one, so there is nothing for it to copy.

A card played on yourself is nobody's business either: "aimed at you" is what
both counters say, and a plus-4 you drew is not aimed at anyone.

The thing that had to move was not in the engine at all. `isInterrupted` already
refused to deal while a card was in flight, and the room's `promptOf` already put
`respond:` ahead of `deal:` — but the full-game driver worked through its deal
queue first, so the opening deal turning up an action card that somebody could
nullify left it asking for a `DealTo` the engine would never take. The room was
right and the test harness was wrong, which is the pleasant version of that
discovery.

**A card handed home may put you over the cap.** A nahhh sends a card back to the
hand that played it, and that hand may since have filled up. It goes back
anyway: a card coming home is not a card you gained — you had room for it when
you played it — and the *next* card that would take you over is the one refused
instead.

**Two beats, not one.** A counter turns over and is read; only then is the card
underneath struck out. Answering in the same breath would hand everybody the
answer and the question at once, which is the thing `PendingOutcome` was written
to stop.

**The window's own clock is twelve seconds**, not the turn's thirty. Thirty is
far too long to hold a whole table for a counter most of them cannot play, and
eight is not long enough to read two cards you have never seen before deciding.
Silence is a pass. Bots let it stand, always — a bot that never counters is a
perfectly good first bot, and what it must not do is stay quiet, because
`deadlineFor` hands out no clock at all when everybody being asked is a bot.

**Three things were wrong when I first looked at it.** The pile offset each card
by seven pixels, so two cards read as one card with a thick edge — the one thing
the component exists to say. The pass button was printed straight across the
local player's hand. And the hit and stay buttons stayed lit through the whole
window, because the client's `isInterrupted` did not know about the stack: the
engine was refusing those moves and the buttons did not know it.

## The money is the score

The third slice: a window between rounds with a shelf of four cards on it and
your own points to spend.

**It is not a fifth phase.** It is `ROUND_END` with a shop hanging off it, and
the client derives its own screen from that. A new value in `GamePhase` is the
one wire change that cannot degrade: a tab that has not reloaded would fall
through `App.tsx`'s switch to `default` and land its player back in the lobby.
This way an older client shows the plain scoreboard and waits for the host,
which is exactly the classic between-rounds behaviour and is correct.

It sits **after** the scoreboard rather than instead of it, so the whole existing
outro chain — the closing animation, the payout, the card, the autostart — is
untouched, and the clock does not start while people are still reading what the
round paid.

**One line of the room does most of the work**, and it is a line of *ordering*:
the shop is dispatched at the very top of `tick`'s non-`PLAYING` block, above the
three lines that tear down `promptKey`, `turnDeadline` and `gate`. The window
keeps a clock and an animation gate of its own and that teardown drops both.

**The stock is minted, and I had that wrong.** The plan said a second conserved
pile — a "reserve" the shelves were dealt out of and unsold cards went back to —
and I wrote it that way in M1 before there was anything to put in it. Building
the shop showed why it does not work: a conserved reserve has to be part of the
deck's card count, so the lobby would advertise "Rolling Rules — 133 cards" for a
deck you play with about a hundred and ten of, and every conservation assertion
would be counting the dealer's stock as if it were on the table. CLAUDE.md
already blesses the answer — "if you need a card that was never dealt, mint it
with a `tmp-` id" — and the user's own decision was that the cap on buying is
*points and slots*, not supply. So the shop mints, `gamblerReserve` is gone, and
`mintedGamblers` goes out on the wire so anything counting the deck can subtract
what the deck never had.

A minted gambler card is the one exception to `tmp-` meaning "gone at the end of
the round", and it is an exception by construction rather than by a special case:
`nextRound` sweeps the hand and the modifier row and has never touched the
gambler zone.

**A purchase moves the scoreboard, not the round.** `Ctx.bank` is the second
thing in the engine that writes a banked score outright, after `swapScores`. An
adjustment rides in `roundAdjustments` until a round is scored, and between
rounds there is no round to score — a purchase held there would land at the end
of the *next* one, the number you are reading while you shop would be a lie until
then, and the scoring floor would quietly refund it.

**Two things the full-game sweep taught me.** A copycat pushing a frame from
inside an effect that `resolveStack` was already running gave the two of them a
fresh loop counter each and a stack overflow rather than the depth guard they
were supposed to share — pushing a frame no longer unwinds, and the one loop
already turning picks it up. And a driver that spent every point it could see
never reached the target score in twenty thousand steps, because in a game where
the money is the score a table that buys everything never wins. That is a true
thing about the mode, and `botBudget` — forty per cent of what you came in with,
and nothing at all once you are within a round of winning — is where the answer
to it lives. The sweep runs the shipped bot's own policy now rather than one
invented for the test.

Five hundred and eighty-six rounds went by in one of those runs before it was
noticed that every score kept snapping back to nothing. The driver plays every
gambler card the moment it can, and that includes playing "fuck it" every single
time anybody goes out.

**The cards were 52 pixels wide** the first time I looked at the finished
screen — the size the in-round shop uses, where it is a small sheet held over a
table. On a screen whose entire purpose is choosing between four cards you have
to be able to read them, so `OfferShelf` takes a size and the shop between
rounds asks for the smallest one at which a card's description renders at all.

## The rest of the cards, and the auction

The last two slices, and between them the set is complete: twenty-two gambler
cards, and a sealed-bid auction for the jackpot at the end of every shop.

**Deflect could be written at last.** It was held back in M2 because every
gambler card up to that point resolved on whoever played it, so turning a frame
round changed precisely nothing. The machinery was left in and the card left
out. It went in the moment there was something to turn.

## Reading a card you have never seen

Four things came out of the first evening anybody played it, and every one was
about size or about being told a rule after breaking it.

**The shop was the worst of it.** Four cards at 92px in the middle third of a
1440px screen, with the description at nine points and the rest of the monitor
empty — the one screen in the game whose whole job is teaching you faces you have
never met. `content-width` caps a page at 60% of a wide screen, which is right
for a column of prose and wrong for a shelf. It now takes the width it needs, at
a new `large` card size, with the auction beside it rather than below the fold on
a two-minute clock.

**Buying and reading became two controls.** Handing the whole decision to one
click meant the only way to look closely at a card was to buy it. The card is now
a reading control and a `buy · 40` button underneath is the only thing that
spends — and when it will not spend it says which of the three reasons it is:
yours, too dear, no room. The mid-round sheet keeps the one-click shape, because
there the question really is only "which one".

**A card now says when it may be played.** Half of what a gambler card costs you
is that it is good at one moment only, and a face that did not name the moment
left you to find out by clicking and having nothing happen. It shares the line
with the rarity: `rare · your turn`, `common · in response`.

**Your own hidden hand was the smallest thing on the table** — five 52px cards,
enough to see that you were holding something purple. It is drawn at the size a
hand is drawn at now. Other seats' face-down racks went up with it, because
counting them from across the table is the whole job of a rack you cannot read.

**One hook for the cards that are about what a round pays.**
`settleGamblerEffects` takes a delta map and returns one, the same shape
`payBounty` has: double down counts the round twice, already down pays a share
to whoever is behind, taxes takes a tithe off everybody else, and a loan comes
due. None of it belongs in `roundScore`, which is about the cards in front of
somebody rather than about the money.

Writing it turned up a real bug, and a quiet one: `enterRoundEnd` built its
result from the snapshot it read at the *top* of the function. That is right for
computing numbers and wrong the moment anything moves a card — so a settled
loan's IOU was collected, the three hundred was taken, and then the old snapshot
was copied over the top and put the card straight back in the tray, free.

**A loan's debt is a card in your own tray**, which keeps rule one intact, needs
no new field on the state, and costs a slot until the round ends. Taking one
with a full hand is therefore impossible — there is nowhere to put the IOU — and
that is the right answer rather than an oversight.

**The cooler revive is more expensive than it looks.** Handing a hand back with
its duplicates still in it means nothing at all unless the duplicates stop
busting you, because `resolveBustAfterGain` would bust you again the instant
anything touched the hand. So it comes with a card that suppresses it for the
round — and the full-game sweep's "nobody holds duplicates without having
busted" invariant, which predates the card, had to be told about it.

**House rules ships with one sub-mode of three.** The spec offers a choice
between "duplicates do not bust but nobody may stay", "everyone must stay after
four cards", and "all point values are inverted". The first two want a mid-round
mutable `RuleSet`, and `RuleSet` is a pure function of `GameConfig` — built
fresh in four places, freely and cheaply, precisely because it cannot change
under anybody. The third already exists as `ANTIMATTER`, and minting one onto
every seat costs nothing and composes correctly with everything else. That is
what it ships as. If the three-way choice turns out to be the point of the card
in play, that is the moment to pay for a mutable rule set — not before.

**Stacked deck shows you five and lets you say which comes next**, rather than
reordering all five. It is the same decision with one answer instead of a
permutation, and a permutation is a prompt this game has no picker for.

**Foreseer was written first of the last five**, deliberately: it is the second
consumer of the per-viewer projection built for the hidden hand and the cheapest
possible proof it works. If the projection were wrong, that card would show
everybody the deck.

**A seventh window.** "Back to the shop" is about the shop rather than about a
round, so `PlayWindow.INTERLUDE` exists. Bending "always" to cover it would have
made "always" mean "except between rounds, unless".

## The auction

One jackpot, and the bid goes over **with "i'm done"** rather than on its own.
That is not a UI convenience — it removes a race. Were bidding and shopping
separate you could bid a hundred and then spend sixty, and the auction would
settle a bid you cannot pay.

The bids live in `Interlude.bids` and are never serialised, the same discipline
`PendingAction.answers` keeps and for the same reason: the auction is secret
without any per-viewer filtering, because there is nothing on the wire to leak.
They appear all at once in `Sale`, after the hammer, and the client draws them
with `Showdown` — whose own doc comment already read "everything answered in
secret, turned over at once", which is a sealed-bid auction verbatim.

A bid over your purse is **clamped rather than refused**, which is the contract
everywhere else in this engine: an illegal target is replaced, a short pick is
filled in, an absent answer is invented. **Nought is not a bid** — it collapses
"nobody wanted it" and "everybody shrugged" into one outcome with one test, and
it stops a table of shrugs handing somebody a free jackpot. Ties go to whoever
is poorer and then by seat, so a replay from the seed settles it the same way
twice; nothing there touches the rng.

The winner pays **after** the table has read the bids, through `Ctx.land` — a
score moving while the bids are still turning over hands everybody the answer
over the top of the question, which is what `PendingOutcome` was written to
stop.

**The lot is rolled blind of what anybody is holding.** A lot that avoided the
cards already in your tray would be a window into the one thing this mode keeps
secret.

**Rigged bid needs no arming mechanism.** It is a passive-window card that sits
in your tray and the auction consumes it, at a tenth over the standing price. It
does nothing when nobody bid — the card's promise is to *outbid the winner*, and
a free jackpot for holding one would make it strictly better than bidding, so
every table would converge on nobody bidding at all.

## What this cost the rest of the game

Almost nothing, and that was the point of doing the harness first. The e2e
suite's `cardsAccountedFor` learned three new terms and its `ephemeralCards`
query — an unscoped `[data-card-id^="tmp-"]` that was quietly counting the dev
panel and any open sheet — was scoped to the board. `playUntil` grew a response
policy and a shop policy, both defaulting to "walk through it". The rules book
grew a fifth page, and the spec that paged through it stopped counting to four
and started reading how long the book is.

All 95 end-to-end specs pass, and one of them plays a whole classic game
asserting the mode never appears anywhere in it — including that the next round
deals straight away, with no window in between.

---

# Paying the table, and the run at the target

> For the outro animation, it would be cool to see every player gain their
> points, before moving onto the scoreboard. Also when somebody is getting
> close, make their bar look more epic.

**✅ Both done.**

## The payout

A round was scored in one stroke: every total on the scoreboard changed at once,
and then a full-screen card came over the table and handed you a summary. The
moment a round is actually about — what everybody made — happened behind that
card, in a single frame, with nothing to watch.

The table is paid a seat at a time now, on the felt it was played on. Each
player's points lift off the hand that made them, fly to that player's line on
the scoreboard, and the total moves when they land. Smallest first, so the
round's best hand is the last thing paid; a seat that made nothing still gets its
moment, because a zero crossing the table is the point of paying one at a time.

The window comes from the server, like every other closing beat: `markRoundBoundaries`
reserves `payoutWindowFor(players)` ahead of the closing card, and the client
spends it — one seat every `PAYOUT_STEP_MS`, scheduled *backwards* from
`roundOutroFrom` so it lands inside the window however long the animation that
ended the round took. The two constants are the same number in two files, and
they are commented as such, the same way the outro windows already were.

The scoreboard is told what to print rather than reading it off the player: the
server banks every score the instant the round is scored, so `totalOf` withholds
a player's delta until their own points have landed on it. Only while a payout is
actually running — a client that reconnects into the middle of a closing window
never saw the round scored, and must not sit on totals that are, as far as it
knows, simply the totals.

## The run at the target

There was no bar. Now each line on the scoreboard has one: how far along the run
to the target that player is, drawn as a pencil line under their name, and it
fills as the payout pushes it. Only in a game that *is* a race to a number — a
game played to a number of rounds has no line to run at, and gets no bar.

Three steps, because "in the running" and "about to take it" should not read as
the same thing across a whole board:

- under 70% — a pencil line, quiet, no colour;
- **close**, from 70% — the fill warms to a muted brick and starts breathing;
- **brink**, from 90% — the bar thickens, goes to the full accent, glows, is
  scratched past the end of the line by a pencil that kept going, the whole row
  warms up behind it, and the name is tagged **match point**.

A bar that shouts from the first round has nothing left to say in the last one,
which is why most of a game is spent in the quiet step.

---

# New card: antimatter

> Give it a player (passive), it turns all of there value card into negative ones
> so (13 becomes -13 and so on). With a swap you can give this card away. The
> player who holds it cant go out.
>
> For antimatter, add in that you cant go out, but if you bust you still lose the
> points.

**✅ Done.** A passive card, dealt from the deck like the discordia — one in *Let
It Ride*, two in *Chaos* — because the way you give it to a player is the swap,
and that only works if somebody can end up holding it in the first place.

`PassiveScoring.NEGATE`: the hand total is turned over before anything else
touches it, so a ×2 doubles the hole rather than digging a second one and a +10
fills a little of it back in. Asked once however many are on the table — two of
them cancelling out is a joke this card is not making.

**It lifts the floor under its own holder.** Every other bad round in this game
is rounded up to nothing unless "extreme" is on. A card whose entire claim is
that a 13 is worth minus thirteen has to mean it, so `floorFor` makes an
exception for whoever is holding one: the round comes off their scoreboard on any
table. That is the card, and it is the only thing in the game besides a house
rule that can put a score below zero.

**And you may not stop.** `Engine.canStay` is public now, because three different
things have to give the same answer or the table breaks in three different ways:

- the engine refuses the move;
- the bots ask before offering to go out — a bot that kept offering a stay the
  table refuses would hold the turn for ever, which is a stall rather than a
  rule;
- the turn clock, which used to send you out when it ran down, now *draws for
  you* instead. Waiting quietly must not be a way off a card that says you cannot
  stop.

Being *sent* out is another matter: a freeze still works on you, which makes
freezing a friend a rescue for once.

**And busting is not a way out either.** It was, for a while: a bust writes a
round off for everybody, so the way to stop under a card that would not let you
stop was to keep drawing until the duplicate came and walk away owing nothing.
That is a *better* round than the one the card was pushing you into, which made
the harshest card in the game a card you wanted, dealt to somebody who then had
nothing to decide.

So `roundScore` returns zero on a bust for everybody except a holder of one,
whose hand is scored the whole way down like any other — the card that busted
them included, because the duplicate lands in the hand before the bust is called.
Being made to draw on now gets worse rather than eventually being over. The floor
was already lifted under its holder, so the hole reaches the scoreboard on any
table without "extreme" being on.

The card's escapes are what they always were minus that one: run all the way to
the flip and hope the bonus covers it, be *sent* out by somebody, or trade it
away.

The client is told which seats may not stop (`cannotStayIds`) rather than working
it out — the rule stays on the server, and the button says "no way out" instead
of being mysteriously dead. It is told the second half the same way
(`bustStillCountsIds`), because every busted seat in this game is struck through
and shown a dead number, and the one seat that is *not* getting away with it must
not be told that it is. The strike through the name stays — out is out; the
strike through the *number*, which says "and it came to nothing", is what comes
off. The round summary shows those seats what they are taking rather than what
the hand added up to.

The rest of the escape is the one the card names: trade it away. `DeckTest` holds the rule
that any deck able to land a curse on somebody has to deal a way to move one, and
it now counts action cards that hand curses out as well as curses in the deck
itself.

**Some flaky specs came out of the swap cards, not this.** Four specs started
failing about one run in three, always the same way: round one ended before the
human ever had a turn, so there was nothing to check. The house deck now deals
two `swap cards` — added so a discordia can be got rid of — and a bot drawing one
can trade a duplicate into your hand and bust you before you have acted, which
the three freezes could always do and now have company at.

All four play the *friendly* deck now, which has no attacks in it. A spec about
the turn clock has to be given a turn, and it should not be at the mercy of a
freeze. Three clean runs of the whole suite since.

That is the test fixed rather than the game, and the game's side of it is written
down under "still open" below.

---

# Bugs

The six from the pass before, what each one actually was, and where it is now.

`✅ fixed` · `🟡 partly` · `⬜ open`

---

## ✅ The coin flip gave its answer before it landed

> Instantly gets the result of coinflip, not waiting for the flip animation, same
> for assassination.

The coin was thrown and paid out in the same transition, so the state that
arrived with the `coinFlip` event already said "bust". The client had nothing to
work with: the seat was busted, the strike was drawn through the name, the hand
was scattering — all of it while the coin was still turning over in the middle of
the table. Same for the bottle: it was still slowing down when its victim's seat
went red.

There was no client-side fix for that worth having. Delaying the animation would
not help, because what spoils it is the state, and the store is a mirror — it is
not the client's place to sit on a fact it has been told.

So the engine has a new primitive: **`PendingOutcome`**, and `Ctx.land`. A card
that is its own animation emits the event that describes what happened, hands the
outcome to `land`, and stops. The room steps it — `GameAction.ResolveOutcome` —
once the table has finished watching, off the same animation gate everything else
already waits on, and the bust or the ×2 arrives in a batch of its own. The slot
machine has worked this way since it was written; the coin and the bottle do now
too.

It is a queue rather than a single outcome, because "double it!" throws the coin
twice and spins the bottle twice, and each of those deserves to be watched and
settled in its turn. While one is outstanding the table is busy — the engine
refuses moves through `isInterrupted`, and the client hides the buttons the same
way it does for a run of forced draws.

Two things fell out of it. The bottle no longer picks a seat that already has a
bottle coming, because neither victim busts until both spins have been watched
and two bottles stopping on the same player is one wasted spin. And a bomb chain
started by a doubled assassination now asks each bomber their question in turn
rather than resolving the second one behind their back.

---

## ✅ Discordia could not be given away

> Not all cards are giveable, for example i cant give discordia to somebody else.

Two faults, and the second one is mine.

The first: `swap cards` only ever offered cards belonging to players still in the
round. By the time somebody wants rid of a discordia, half the table is out — and
a seat that has gone out is still holding its cards. `cardsOnTable` now reaches
every seat, whatever became of it, which is the same rule spin the table already
plays by (below, and it is bug six).

The second: I put a discordia in the *Let It Ride* deck, which deals no card that
can move a modifier. A card whose entire design is "you can push this onto
somebody else" is a flat penalty in a deck with no way to push it, so the card
that shipped was not the card that was designed. The deck now deals two
`swap cards` alongside it, and `DeckTest` holds the rule: **a deck that deals a
card nobody wants has to deal a way to pass it on.**

---

## ✅ Swap would not touch a seat holding only modifiers

> I cant swap with somebody if they only hold passive hands.

`swap hands` asked for `OTHER_ACTIVE_WITH_CARDS`, which counts the hand and
nothing else. That was right when the card only moved hands; it has moved the
modifier row as well since the last pass, so a player sitting behind nothing but
a ×2 is very much worth swapping with and was not being offered.

New rule, `OTHER_WITH_ANYTHING`: anybody else holding anything at all, in either
pile.

---

## ✅ A half-made pick could not be taken back

> Cant unselect on swap card.

`swap cards` wants two cards and the first click latched one with no way to undo
it. The answer only goes to the server on the click that *completes* the pick, so
everything before that was always the client's to change — it simply had no way
to say so.

Clicking a picked card now takes it back out, for as long as the pick is
unfinished (`canUnpickCard`). Once the last card lands the answer is on its way
and is not this client's to change any more, which is why the rule is "while the
answer is still short" rather than "always". The card says so: a picked card that
can still be taken back gets a pointer and a dashed outline on hover.

---

## ✅ The second mutate never opened its shop

> Getting 2 times the mutate card does not work on the second time.

This one was a good bug. A prompt raised by something other than a card being
drawn — a mutate's shop, a bomber's question — stands a minted card in for the
one that caused it, and the id came off a counter that lived on the *transition*.
A transition is one moment; a game is many. So the counter started again at
nought every time, and every mutate shop in a game was raised behind a card
called `tmp-mutate-0`.

The client keys its latched answer on that id. The second shop looked, to the
client, exactly like the one it had already answered — so the pick was refused,
the sheet sat there inert, and the prompt eventually timed out.

The counter is `GameState.minted` now, so an id is minted once per game and never
again. That also quietly fixes a family of things nobody had noticed: two ×2s
minted a turn apart were both `tmp-doublePoints-0`, and two cards on one table
wearing the same id is a swap that can pick the wrong one and a React key that
collides. `MintTest` covers all three.

---

## ✅ Swap cards could not reach a seat that was out

> Cant swap cards with players who busted or gone out.

The same `cardsOnTable` filter as bug two. Every card on the table is on offer
now, in front of anybody. A banked hand is still points, a modifier row is still
where a discordia is sitting, and both seats are re-checked afterwards — a hand
that has already gone out and is handed a duplicate busts like any other.

This makes `swap cards` the counterpart to the spin: the spin pushes a whole
busted hand at somebody, and this pushes one card.

---

# The last of the open list

Everything left over from the passes above, except the sound one — a warning that
runs to its end after you have acted is fine.

## ✅ Bots read a discordia now, and get rid of one

`botPick` ranked seats by what was on the table in front of them. It ranks them
by that plus what they pay for being aimed at, which is the whole of why anybody
attacks a discordia holder.

The bigger half was the trade. A card-picking prompt used to get
`rng.shuffled(validCards)` — a legal pick, and never a considered one, so a bot
holding a card nobody wants held it until the round ended. `botCardPicks` plays
it as the trade it is: lead with the worst thing it is holding, and take the best
thing somebody else is holding that will not collide with a card it already has.
An "all in" is told apart by the prompt asking more than one player, and bets
from the middle of the hand, because the two ends of the table are what that card
punishes.

The three of them are pure functions at the top of `Rooms.kt` rather than methods
on the room, so `BotTest` can ask them what they would do without standing up a
table to ask.

## ✅ Steal reaches the modifier row

`Ctx.stealRandom` only ever looked at the hand, so a card in front of somebody
could be swapped for but never taken. It reads `hand + passives` now and puts
what it took in whichever pile it belongs in, and `steal` asks for
`OTHER_WITH_ANYTHING`, so a seat sitting behind nothing but a x2 is worth
robbing.

The interesting half is that this includes the cards nobody wants. Reach into the
hand of the player carrying a discordia and you may come away with the discordia
— so a cursed seat is both worth attacking and worth being careful about, which
is the most interesting a target can be.

## ✅ The seat says what the cards are worth, not what they add up to

An antimatter holder read "18" on the felt and −18 on the summary. Both were
true; only one was the answer to the question the player was asking.

`Player.handValue` stays the physical sum, because that is what the bust
threshold counts — a hand does not stop being twenty-two because it is worth
minus twenty-two. The view carries `handWorth` alongside it: the same total, with
its sign. The client prints that and works nothing out, and an older server that
does not send it falls back to the plain total.

## ✅ A toll is worth the same on a bad round as on a good one

"Anyone who plays an action card on you takes 10 points off you" is read by
everybody as ten points. It was ten points, or whatever the round happened to be
worth, whichever was less — every round is floored at nothing without "extreme",
so a toll charged on a two-point round cost two.

`roundTolls` records how much of a round was *taken* rather than not earned, and
`floorFor` lets the floor down by exactly that much. What a player earns still
cannot put them in the red. What was taken from them can, and only that far — an
anti-flip deduction, which is a thing you give up rather than a thing taken from
you, is untouched and still stops at nothing.

## ✅ The showdowns are read before they are paid for

Both of the cards that ask the whole table at once resolved in the same breath as
the reveal: `comeback` swapped the two scores while the hands were still being
shown, and `all in` put the halving cards down underneath a showdown nobody had
finished reading.

Both land now, on the `PendingOutcome` the coin flip introduced. It grew a
`targetIds` for the all in, which settles with both ends of the table at once —
asking the same question of four seats one after another would be four waits for
one moment.

---

# Nothing in front of you is safe, and the table stays together

Three from the same pass. The first two are the same sentence twice — a card
that takes something reaches the cards, wherever they are lying and whatever
became of the seat in front of them — and the third is about what happens after
the last round.

## ✅ Spinning the table left the modifier rows behind

> Spinning the tabel does not spin passives.

`rotateHands` moved every hand one seat and every modifier row nowhere, so a ×2
stayed to double a hand it had never seen and an antimatter stayed to curse one
that arrived clean. It now slides both, which is what `swap hands` has always
done and for the same reason: everything in this game is a card, so what is in
front of you is one thing rather than two piles that some cards reach and others
do not.

It cuts both ways, which is the interesting part. The cooler that made a hand
full of duplicates survivable travels with it, so the seat it lands on is not
busted by a hand its owner was allowed to keep — and the second life you were
sitting behind has gone off with your own hand and is protecting somebody else
by the time the wreck arrives.

The client needed nothing at all. `SpunHand` already wrapped both piles, so the
modifier row had been visibly sliding in from the donor seat this whole time
while the state said it had not moved. The animation was right and the rules
were wrong.

## ✅ A seat that is out could not be struck, stolen from or swapped with

> when somebody is out you cant strike them, or swap hands etc.

Only two cards could reach a finished seat — `swap cards` and the spin, both of
which point at the cards themselves rather than at a player — and everything
that pointed at a *player* was filtered down to whoever was still in the round
unless "extreme" was on.

Which seats a card may be aimed at now lives on `TargetRule` as
`reachesFinished`, because it is a fact about what the card takes rather than a
house rule. A strike takes a card, a steal takes a card, a swap takes a whole
row, and every one of those is still lying face up in front of somebody who
banked or busted, still worth points to whoever ends up holding it. A freeze
takes the rest of your round and there is no rest of it to take, so freeze,
draw 3 and skip are still offered only to seats that can do something about
them — a card offered against nothing is a card spent for nothing, which is what
`fizzle` and `skipHolding` exist to prevent.

`ACTIVE_WITH_CARDS` was renamed `ANYONE_WITH_CARDS` because the old name had
become a lie, and `OTHER_ACTIVE_WITH_CARDS` was deleted: no card had named it
for a long time. "Extreme" keeps its blanket — under it *every* card may be
pointed at a seat that is out, warts and all — and is no longer the only way to
strike a banked hand.

Two things on the felt had to follow. A seat that is out is drawn dimmed, and
that was tested *before* whether it was a legal target, so a seat you could now
strike still read as unavailable; targetable wins now. And the "no target" badge
was only drawn on seats still in the round, so an out seat that genuinely was
not a legal target said nothing at all.

## ✅ "Play again" broke the table up

> When you click play again the lobby persists.

It called `leaveGame` — the same thing the pause menu does — so the host walked
out of the room and everybody else was left on a results screen in a room that
could never start another game. Playing again meant one person re-hosting and
four people typing a new code in.

There is a `PLAY_AGAIN` now, and like every other message that decides something
for the whole table it is the host's. It puts the room back in **its own
lobby**: same seats, same bots, same settings, scores wiped, and the code on the
wall unchanged. The lobby rather than a fresh deal on purpose — a table that has
just played usually wants to change something first, and dealing straight into
round one would make it the one button in the game you cannot take back.

The transition is built out of `newGame` rather than by copying the finished
state and clearing what must not survive, because "the field nobody remembered
to clear" is the exact bug it exists not to have. Two counters are carried
across on purpose — the ones that name minted cards and stack frames, which
have to keep climbing for the life of the *room* — and one thing is dropped: a
seat whose tab has gone. It would be dealt cards nobody plays, hold the turn
clock for its full run every round and count against the room filling up. Same
rule the shop already uses; somebody who comes back simply sits down again.

Everybody who is not the host gets "waiting for host…", the way they do between
rounds, and both of them get a way out — leaving used to *be* the play-again
button, and taking it away without a replacement would strand a guest on a
screen with no controls.

## ✅ ...and the spin says which way it is going before you commit

> for spin the card also on the table around the deck show an arrow when
> hovering over a direction so its more clear where it moves

"left" and "right" on two buttons say nothing about a round table: which way
your hand actually travels is a fact about the seats, not about the words. Hover
either option and a ring is drawn round the deck, turning the way the table
would. It is deliberately the shape the payoff comes in, and it costs nothing to
look — which is the point of showing it before the click rather than after.

---

# Still open

- **⬜ A round can be taken off you before you have had a turn.** An opening card
  is played the moment it is dealt, so a freeze — or now a swap that hands you a
  duplicate — can end your round before you have touched a card. It has always
  been possible; the house deck's two new `swap cards` made it common enough that
  four end-to-end specs started noticing. Sitting down and being out before your
  first turn is a bad first thirty seconds, and there is no rule in the game that
  says it cannot happen. Whether there should be — nothing may end a round that
  has not been played, or opening cards resolve only once everybody is dealt in —
  is a design question rather than a bug.
- **⬜ Nothing can stop a sound once it has started.** `play` hands back nothing,
  so the five-second clock warning runs to the end even if you act a second
  later. Left alone on purpose: that is a sound finishing, not a rule breaking.
