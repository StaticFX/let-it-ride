# Backlog

What was asked for, what it turned into, and what is still open.

`✅ done` · `🟡 partly` · `⬜ not started`

---

# Rule one: everything is a card

> Rule number 1 in this game is that everything is a card, also the passives of
> the players they have.

**✅ Done.** There used to be two mechanisms for "you are under this until the
round ends": passive cards, and *marks* — a `Set<String>` on the player,
deliberately not cards, so that nothing could take one off you. The four marks
are now `PassiveCardDef`s and `Player.marks` is gone.

| was a mark | is now |
|---|---|
| `bomber` | `BOMBER`, the card the suicide bomber hands you |
| `noFlip` | `NO_FLIP`, "just one more" |
| `mustFlip` | `MUST_FLIP`, "unlucky 7" — `PassiveScoring.VOID_UNLESS_FLIP` |
| `halved` | `HALVED`, what an "all in" costs the two ends — `PassiveScoring.HALVE` |

None of them is ever shuffled into a deck. They are minted by whatever causes
them (`Ctx.grantEffect`) with a `tmp-` id, so they are dropped at the end of the
round exactly where a mark used to be wiped, and `PassiveCardDef.deckable` keeps
them out of `sanitizeDeck` and out of the deck builder.

What this bought, which is the point of asking for it: an effect card lies on the
table like everything else, so it can be swapped, traded, and pushed onto
somebody who does not want it. `MarkSlip.tsx` and the catalog's `marks` are gone
with it — the modifier row draws them, because they are modifiers.

---

# Swap takes everything

> Swap-card swaps everything on a hand, including passive cards etc.

**✅ Done.** `Ctx.swapHands` moves the modifier row along with the hand, and
`SWAP` re-checks both seats afterwards — whole rows move, so no duplicate can
appear, but "blackjacking" caps the total and the hand coming back can be over
it. That re-check was missing before and is a bug fix on the way past.

---

# Spin the table spins the whole table

> Also adjust the spin the table to spin the hand from every player, including
> busted, and go out ones.

**✅ Done.** `Ctx.rotateHands` no longer filters to the players still in the
round. Every seat takes part, and every seat is re-checked afterwards —
`resolveBustAfterGain(finishedToo = true)`, which is new, because a hand can land
on somebody who has already finished and a banked hand holding two of the same
card is a bust however quietly it came by them.

A busted hand is still holding the duplicate that killed it, so pushing one onto
the player in front is now the reason to play the card. It costs the seat that
already busted nothing.

---

# Animations that wait

> The game does not wait properly, and the coin flip looks weird. A lot of
> animations happen too fast for the player to realize what happens.

**✅ Done.** Three separate faults, all of which had the same symptom.

**The gate did not cover the wait.** An animation held back until the played card
landed (`SMASH_LAND_MS`, 560ms) still only held the table for its own length, so
the server was released while the animation was 560ms from finishing — every
time, for every card. `pushAnimation` and `startFlights` now hold for the wait
*plus* the animation. It costs whoever is on the clock half a second, which is
the cheaper of the two things to spend.

**The closing window was shorter than the animation.** A coin called wrong sends
a coin flip and a bust in one batch, and `outroPreambleFor` read the bust — so the
closing card came down 2200ms in, on a coin that had 2600ms of turning left. It
now takes the longest window of anything in the batch, and the coin, the bottle
and a points transfer each have one.

**The coin was flattening its own 3D context.** `.coin-toss` faded out on its last
frames, and `backface-visibility` stops working the moment opacity drops below 1
— so for the last tenth of every throw the coin read "heads" and "tails" over
each other. The fade lives on a wrapper now. The bottle had a quieter version of
the same problem: it slowed onto its victim at 82% of an animation that was being
cut off early, so nobody ever saw where it stopped. It stops at 60% now.

Durations are handed to the overlays rather than kept in them: `GameAnimation.ms`
carries the paced figure out of `ANIMATION_TTL_MS` into `--coin-dur`,
`--bottle-dur`, `--fizzle-dur`, so an animation written in CSS and the hold the
table is under cannot say two different numbers.

---

# A card that could do nothing

> When a card has no use, it needs a special animation > 2000ms.

**✅ Done.** 2400ms, and it is an animation rather than a line of text:
`FizzleNote` holds the card up, strikes it out by hand, and drops it towards the
discard pile. It also says *why* where the reason is not obvious — a comeback
drawn by somebody who is not last, an all in with too few hands, a shop with
nothing you can afford — because "had nobody to hit" was only ever true of some
of them.

---

# Points changing hands

> Point transfer ingame. It is possible to transfer points ingame, this needs a
> special animation.

**✅ Done.** `Ctx.transferPoints(from, to, points)` puts both halves in
`roundAdjustments`, so it shows up on the summary as a line rather than as two
scores that quietly moved, and — with "extreme" off — the floor at scoring time
is what stops it putting anybody in the red. It emits
`GameEvent.PointsTransferred`, and `PointsFlight` lifts a chip off the seat that
paid, carries it over the felt and drops it on the seat that collected.

The mechanic is general; discordia is its first user.

---

# Negativity

> Trading negative passives or busting each other is a game mechanic which adds a
> fun twist to the game.

**✅ Done**, in three places rather than one, because it is a theme rather than a
feature:

- the effect cards above are cards, so a bomb or an unlucky 7 can be traded away
  with `swap cards` — which is what "trading negative passives" needs to be true;
- discordia is a card whose whole purpose is to be got rid of;
- spin the table pushes a busted hand onto somebody who had already banked, which
  is busting each other with no card of your own spent on it.

Negative cards are priced at nought, and `Ctx.offersFor` will not put a card
priced at nought on the shelf: a shop that sold you a discordia would be selling
a way to hurt yourself.

---

# Discordia

> The player who has this card has a negative effect. Whenever player A gives an
> action card (freeze etc.) to player B (with discordia), player A gets 10 points
> from player B.

**✅ Done.** `DISCORDIA`, a passive card dealt from the deck — one in *Let It
Ride*, two in *Chaos*.

`PassiveCardDef.spite` is what the holder pays anybody who aims an action card at
them, and `Engine.payToll` reads it off the target rather than writing it into
any of the nineteen cards that can trigger it: the card that charges the toll is
the one being aimed *at*. Paid once however many times "double it!" fires the
effect — what is resented is being played on, not what the play then did — and
paid before the effect, because the card has changed hands by then and a freeze
that ends the round must not swallow the toll it earned. A card played on
yourself costs nothing, so "womp womp" does not charge its holder for their own
freeze, and a house rule asking a question is not a card being played.

So it is worth attacking whoever is holding one, and worth not being the one
holding it, and the way out is to trade it to somebody else.

---

# The new samples

> Ive also added in new sounds effects and removed the old button click, please
> wire them up.

**✅ Done.** Three of them, and one small change to how `sfx.ts` thinks about a
sound.

**`button-clicks/Click_1.wav`, `Click_2.wav`** replace `button-click.m4a`, which
is gone. A sound is now one file *or several to choose between* — `SOURCES` takes
an array, everything downstream holds a list of takes, and a play picks one that
is not the one it just played. The click is the only sound you hear often enough
for a single sample to start reading as a machine; its pitch spread came down
from 0.11 to 0.07 now that two takes are doing most of that work.

**`given-action-card-to-player.wav`** is `actionLanded`, played from the
`actionPlayed` event at `SMASH_LAND_MS` — so the card is heard where it comes
down rather than where it was thrown from. The coin, the bottle, a showdown and a
toll all used to announce themselves individually with the *draw* sound at that
exact moment; they arrive in the same batch as an `actionPlayed`, so those four
lines are gone and this is the one place any of them says so. `actionCard` now
means only "an action card came off the deck".

**`timer-less-than-10s.wav`** is `timerRunningOut`, played once when your own
clock enters its closing stretch — the same moment the countdown moves to the
middle of the table and the felt starts to breathe. That rule lived in
`GameBoard`; it is in `useGame` now (`clockIsClose`, `clockUrgency`), because a
sound and a vignette disagreeing about when a turn is nearly over would be worse
than either.

It is once per *turn*, not once per deadline: the server hands back the time a
table spent animating, so a single turn carries several deadlines and keying on
one would sound the warning again every time a card was played inside the last
few seconds.

The three new samples are recorded far hotter than the originals — RMS 0.08 to
0.16, against the keystroke's 0.0079 — so they are the only three with a gain
below 1. Each is matched to the sound it stands beside rather than guessed at;
the arithmetic is in the comment over `GAIN`.

---

# Still open

- **⬜ Bots do not read discordia.** `botPick` still aims at the legal target with
  the most on the table. That happens to be right often enough — the holder is
  worth hitting — but a bot will never trade one away, and a bot holding one has
  no idea it should.
- **⬜ Steal cannot take a modifier.** `Ctx.stealRandom` only reaches the hand, so
  a negative card can be swapped away but never stolen. Whether that should
  change is a rules question: a steal that could take a bomb is a steal that can
  be aimed at yourself.
- **🟡 A toll cannot go below nothing.** Without "extreme" a player whose round is
  worth less than the toll simply scores nought, and the difference is not taken
  off their banked score. It is the same floor anti-flip has always had, and it
  means a toll charged on a bad round is worth less than one charged on a good
  one. Deliberate, but worth writing down.
- **⬜ Nothing can stop a sound once it has started.** `play` hands back nothing,
  so the five-second clock warning runs to the end even if you act a second
  later. It wants a handle on the source node to be stoppable.
