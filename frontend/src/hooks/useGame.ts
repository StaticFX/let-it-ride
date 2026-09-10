import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore, findAction } from '../state/gameStore'
import { send } from '../net/client'
import { play } from '../audio/sfx'
import type { SoundName } from '../audio/sfx'
import type {
  ActionCardInfo,
  AnimationGate,
  Card,
  GameEvent,
  Offer,
  Player,
  ResponseFrame,
} from '../game/types'
import { modeOf } from '../game/types'

// ─── Animations ───

/**
 * Everything on screen carries `id` — unique per playing — and `ms`, how long
 * it has been given. `ms` is the paced figure out of [ANIMATION_TTL_MS], handed
 * to the overlay rather than kept in it, so an animation written in CSS and the
 * hold the table is under can never say two different numbers. Anything with a
 * duration of its own reads it; the rest ignore it.
 */
export type GameAnimation =
  /**
   * How hard to throw the viewport. A `slam` is an action card landing on
   * someone; a `bust` is the round ending under a player, and gets the harder
   * of the two — see `.shake` / `.shake-bust`.
   */
  | { type: 'screenShake'; id: string; ms: number; strength: 'slam' | 'bust' }
  | { type: 'impact'; id: string; ms: number; targetId: string }
  | { type: 'smash'; id: string; ms: number; targetId: string; cardDefId: string }
  | { type: 'freeze'; id: string; ms: number; playerId: string }
  | { type: 'drawThree'; id: string; ms: number; playerId: string }
  | { type: 'flip7'; id: string; ms: number; playerId: string }
  | { type: 'timeout'; id: string; ms: number; playerId: string }
  | { type: 'fizzled'; id: string; ms: number; playerId: string; cardDefId: string }
  /**
   * A second life being spent — see [SecondLife]. All three cards travel with
   * it because none of them can be found in the state: the duplicate was
   * discarded and the card that saved them left the modifier row, both inside
   * the transition that announced this.
   */
  | {
      type: 'secondLife'
      id: string
      ms: number
      playerId: string
      card: Card
      matched?: Card
      saver?: Card
    }
  /** The coin lands on `result`; `call` is what the player said before it flew. */
  | { type: 'coinFlip'; id: string; ms: number; playerId: string; call: string; result: string }
  /** The bottle stops pointing at `victimId` — the server picked, never the client. */
  | { type: 'bottleSpin'; id: string; ms: number; victimId: string }
  /** Every hand in `playerIds` slid one seat; see [SpunHand] for the slide itself. */
  | { type: 'tableSpun'; id: string; ms: number; direction: string; playerIds: string[] }
  /** Points crossing the table from one seat to another — see [PointsFlight]. */
  | { type: 'pointsTransferred'; id: string; ms: number; fromPlayerId: string; toPlayerId: string; points: number }
  /**
   * A gambler card coming out of a hidden hand and turning face up — see
   * [GamblerReveal]. `firstSeen` is the table's, not this player's: it decides
   * whether the card is held long enough to be *read* or only recognised.
   */
  | { type: 'gamblerPlayed'; id: string; ms: number; playerId: string; card: Card; firstSeen: boolean }
  /** A drawn card handed straight on to somebody else — see the "redirect" card. */
  | { type: 'redirected'; id: string; ms: number; fromPlayerId: string; toPlayerId: string; card: Card }
  /** A gambler card struck out by another one — see [CounteredCard]. */
  | { type: 'countered'; id: string; ms: number; playerId: string; card: Card; returned: boolean }
  /**
   * Everything answered in secret, turned over at once — the comeback's two
   * throws or the all in's whole table of bets. See [Showdown].
   */
  | {
      type: 'showdown'
      id: string
      ms: number
      title: string
      sides: { name: string; label?: string; card?: Card; lost?: boolean }[]
      footnote?: string
    }

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

type AnimationSpec = DistributiveOmit<GameAnimation, 'id' | 'ms'>

/**
 * A played action card flies from the table over the seat it was pointed at,
 * hangs there for a beat, and comes down on it.
 */
export const SMASH_MS = 900
/** How far into that flight the card actually lands. Keep in step with `targetSmash`. */
export const SMASH_LAND_MS = 560

/**
 * How long a second life takes to be spent — see [SecondLife].
 *
 * The longest animation the client plays, and the only one that is a scene
 * rather than a beat: the card that would have ended the round is carried up
 * into the middle of the screen and torn in half by the one being spent to stop
 * it, which then goes up and out of the top. It was a green caption above a seat
 * for most of this game's life, and the caption never once said what had been
 * given up — the duplicate is discarded and the second life leaves the modifier
 * row inside the same transition, so a player watching saw a card arrive that
 * was not there and a card leave that they had not chosen to spend.
 *
 * Its window on the other side is `OUTRO_AFTER_SECOND_LIFE_MS` in `Rooms.kt`,
 * and the gate has to clear it: lengthen this and both have to follow.
 */
export const SECOND_LIFE_MS = 5000

/**
 * How far into that the tear actually happens. The 58% frame of the
 * `secondLife*` keyframes in `index.css`; move one and the sound comes off the
 * card before it has reached it.
 */
export const SECOND_LIFE_RIP_MS = 2900

/**
 * How long each animation stays on screen before it clears itself — and, since
 * every one of these registers a [hold], how long the table is held on it. Two
 * ceilings apply to anything added here:
 *
 * - the server gives up on a gate after ANIMATION_GATE_MAX_MS (8000ms), and
 *   gated time is handed back to whoever is on the clock, so a long animation
 *   never costs anybody their turn but does hold the table. An animation held
 *   back behind a played card costs SMASH_LAND_MS on top of its own length —
 *   behind a gambler card, GAMBLER_LAND_MS — so the real ceiling for one of
 *   those is nearer 7100ms;
 * - a card that can end the round is not gated at all — the round is over — and
 *   the closing card comes down on whatever the server's `outroPreambleFor`
 *   allowed for it. The coin, the bottle, a toll, a bust and a save can all end
 *   a round, and each has its own window there; lengthen one here and the
 *   number in `Rooms.kt` has to follow it.
 */
const ANIMATION_TTL_MS: Record<GameAnimation['type'], number> = {
  screenShake: 600,
  impact: 900,
  smash: SMASH_MS,
  freeze: 1800,
  drawThree: 1400,
  flip7: 3200,
  timeout: 1500,
  // Long enough to read what the card was and why it did nothing. This is the
  // one animation nothing else explains: the card is simply gone.
  fizzled: 2400,
  // The longest animation in the game, and the one with the most to say: see
  // SECOND_LIFE_MS, which is written down there rather than here because
  // `outroPreambleFor` in `Rooms.kt` has to be kept in step with it.
  secondLife: SECOND_LIFE_MS,
  // The throw and the landing, and then a beat to read the face it landed on.
  // Cutting this to fit the round-ending window is what used to snatch the coin
  // away in the middle of its last turn.
  coinFlip: 2600,
  // ...and the same for the bottle, which used to be taken off the table before
  // it had finished slowing down, so nobody ever saw who it stopped on.
  bottleSpin: 2400,
  // The hands slide for TABLE_SPIN_SLIDE_MS; the rest is the swirl clearing off.
  tableSpun: 1000,
  pointsTransferred: 1600,
  // Long enough to read four names and their cards, and no longer — it holds
  // the table while it is up.
  showdown: 2600,
  // A card the table has already seen. See GAMBLER_REVEAL_FIRST_MS for the
  // other length, which is the one that matters.
  gamblerPlayed: 1600,
  // The card is already on screen and the whole animation is it changing hands.
  redirected: 1400,
  // The second beat of a counter, in a gate of its own: the counter turns over
  // and is read first, and only then does the card underneath get struck out.
  // You see the answer, and then you see what it did to the question.
  countered: 1600,
}

/**
 * How long a gambler card is held up the first time this table has seen it.
 *
 * Every other number above was written for a card you already know: a freeze is
 * 1800ms because you know what a freeze is and the animation only has to tell
 * you *who*. A card out of a hidden hand has to say who, what, and what that
 * even means, to three people who have never seen the word before — which is a
 * name and a sentence, and about four seconds of somebody's attention.
 *
 * It is the longest thing the client can ask the table to wait for, and the two
 * server-side numbers that have to clear it are ANIMATION_GATE_MAX_MS and
 * `OUTRO_AFTER_GAMBLER_MS` in `Rooms.kt`. Lengthen this and both have to follow.
 *
 * Whether it applies is the *server's* answer, not this client's: whoever
 * played the card owns the animation gate, and they are the one person who
 * certainly knows what it does. A client that only slowed down for cards *it*
 * had not seen would let them wave it past everybody else.
 */
export const GAMBLER_REVEAL_FIRST_MS = 4200

/** How far into either of those the card has landed and settled. */
export const GAMBLER_LAND_MS = 900

/**
 * One shared empty list for "no cards picked yet". A fresh `[]` every render
 * would change identity every render, and everything downstream of a pick is
 * memoised on it.
 */
const EMPTY_PICKS: string[] = []

/** ...and the same for "no cards", where a fresh `[]` would re-render the tray. */
const EMPTY_CARDS: Card[] = []

/** ...and for "nothing is in flight", which is nearly always. */
const EMPTY_FRAMES: ResponseFrame[] = []

/** ...and the same for "no game yet", for the same reason. */
const EMPTY_PLAYERS: Player[] = []

/** ...and for a prompt that is not a shop, which is all but one of them. */
const EMPTY_OFFERS: Offer[] = []

/** Lets a sound land with the animation it belongs to rather than ahead of it. */
function playAfter(delayMs: number, sound: SoundName): void {
  if (delayMs <= 0) play(sound)
  else window.setTimeout(() => play(sound), delayMs)
}

/**
 * A bust plays in three beats: the card that did it is held over the hand and
 * then brought down on it, the pair that clashed is called out, and the hand
 * scatters. `card` and `matched` come straight from the server, so the table can
 * point at the exact two cards rather than just flashing red.
 *
 * The first beat only happens when the card came off the deck — see `card`. A
 * bust inflicted by another card has already been announced by that card, and a
 * second thing flying at the same seat in the same moment reads as two events
 * rather than one.
 */
export interface BustAnimation {
  playerId: string
  cardId?: string
  matchedId?: string
  /**
   * The card to carry up over the seat, or null when there is nothing to carry
   * — a coin called wrong, a bottle, a hand that busted on a card it was given.
   */
  card: Card | null
  /** The flight's own budget, paced, so the CSS and the hold say one number. */
  ms: number
  phase: 'hover' | 'reveal' | 'scatter'
}

/**
 * The busting card's whole flight: off the deck, up over the seat, held there,
 * and down.
 *
 * Most of it is the hold. A bust is the one thing at this table that happens
 * *to* somebody rather than being done by anybody, and it used to arrive with no
 * warning at all — the card slid into the fan and the strike was already through
 * the name before it landed. So the card is carried up instead and left hanging
 * where everyone can read it, which is the only beat in the game between finding
 * out and knowing. The hand dims underneath it and the card it is about to clash
 * with lights up, so what is coming is legible before it arrives.
 */
export const BUST_CARD_MS = 3000

/**
 * How far into that flight the card actually lands, which is where the shake and
 * the noise go. Keep in step with the 75% frame of `bustCard` in `index.css` —
 * move one and the table stops flinching when it is hit.
 */
export const BUST_LAND_MS = 2250

export const BUST_REVEAL_MS = 1100
export const BUST_SCATTER_MS = 1000

/**
 * A card visibly moving from one seat to another. A steal sends one; a swap
 * sends two, crossing, which is why this is a list rather than a single
 * animation at a time.
 */
export interface CardFlight {
  /** Unique per flight, so two cards crossing are two elements and not one. */
  id: string
  fromPlayerId: string
  toPlayerId: string
  card: Card
}

const STEAL_MS = 1100

/** The slot machine spins until the card it landed on actually arrives. */
export interface SlotsAnimation {
  playerId: string
  card: Card | null
}

/**
 * The machine reports in through `onDone`, so this is only the backstop for the
 * one case it cannot: an empty deck at spin time leaves it with no card to land
 * on and it keeps turning.
 */
const SLOTS_MAX_MS = 3200

/**
 * The round's payout, seat by seat, on the table that made it — see
 * [startPayout]. The server reserves the window before the closing card comes
 * over and hands it back as `roundOutroFrom`; these are what it reserved it
 * with, and `payoutWindowFor` in `Rooms.kt` is the other half of them.
 */
const PAYOUT_LEAD_MS = 400
const PAYOUT_STEP_MS = 560
const PAYOUT_TAIL_MS = 600

/** How long a seat's points take to reach the scoreboard once they are up. */
export const PAYOUT_FLIGHT_MS = 420

/**
 * How much clock is left before the countdown stops being a detail in the
 * corner: it moves to the middle of the table, the felt starts breathing, and —
 * if the clock is yours — you hear it.
 *
 * Whichever comes first, so it reads as the closing stretch of the turn rather
 * than a fixed count: on the shortest clock the lobby offers, ten seconds, a
 * flat ten would put it at the deck for the whole turn and the corner would
 * never be used at all.
 */
const CLOCK_CLOSE_MS = 10_000
const CLOCK_CLOSE_FRACTION = 0.4

/**
 * The least time between two warnings. A deadline is not stable — the server
 * hands back whatever the table spent animating, so one turn carries several —
 * and keying the warning on the deadline itself would sound it again every time
 * a card was played inside the last few seconds. Longer than the stretch and
 * longer than the sample, so it is one warning per turn however the deadline
 * moves underneath it.
 */
const CLOCK_WARNING_GAP_MS = 12_000

/**
 * A batch of events the server is holding the table on. It will not deal, move
 * the turn on, or accept a move until this client reports the animation over —
 * which is what stops the next card landing on a bust that is still scattering.
 *
 * The server never learns how long anything takes. It waits to be told, and
 * gives up on its own timeout if this client goes quiet.
 */
interface OpenGate {
  id: number
  /** Animations still running for this batch. */
  holds: number
  acked: boolean
}

/**
 * A seat being paid what its round made: the points are up and on their way to
 * the scoreboard. One at a time, so every player's round is watched rather than
 * four totals changing at once.
 */
export interface PointsAward {
  /** Fresh per award, so two seats in a row are two flights and not one. */
  id: string
  playerId: string
  points: number
}

export interface TurnTimer {
  remainingMs: number
  totalMs: number
  fraction: number
}

export function useGame() {
  const state = useGameStore((s) => s.state)
  const localPlayerId = useGameStore((s) => s.localPlayerId)
  const catalog = useGameStore((s) => s.catalog)

  // The server's own array while there is one, so its identity only changes
  // when a push actually changed the players — anything memoised on it would
  // otherwise recompute every render.
  const players: Player[] = state?.players ?? EMPTY_PLAYERS
  const round = state?.round ?? 0
  const phase = state?.phase ?? 'LOBBY'
  const turnIndex = state?.turnIndex ?? 0

  const me = players.find((p) => p.id === localPlayerId)
  const meIdx = players.findIndex((p) => p.id === localPlayerId)
  /**
   * Everyone else, in the order they will actually play, starting with whoever
   * follows me. Seats are handed out along this list, so the table reads round
   * the way the turn does — filtering the server's array instead started the
   * seating at player 0, which only lined up when I happened to be last.
   */
  const others = meIdx < 0
    ? players
    : [...players.slice(meIdx + 1), ...players.slice(0, meIdx)]
  const currentPlayer = players[turnIndex]

  // ═══════════════════════════════════════════
  // Animation state — everything here is driven by server events
  // ═══════════════════════════════════════════

  /**
   * How fast this server is running the table. Always 1 in a real game; the
   * end-to-end suite turns it down so a round it has to sit through costs
   * seconds rather than half a minute. Applied in one place — [hold] — because
   * every beat the table waits out goes through it.
   */
  const pace = catalog?.pace ?? 1
  const paced = useCallback((ms: number) => Math.max(1, Math.round(ms * pace)), [pace])

  const [animations, setAnimations] = useState<GameAnimation[]>([])
  const [bust, setBust] = useState<BustAnimation | null>(null)
  const [flights, setFlights] = useState<CardFlight[]>([])
  const [slots, setSlots] = useState<SlotsAnimation | null>(null)
  /** The seat being paid right now, and everyone already paid. */
  const [award, setAward] = useState<PointsAward | null>(null)
  const [paidIds, setPaidIds] = useState<string[]>([])
  /**
   * Whether a payout is actually running. A client that arrives in the middle
   * of one — a reconnect during the closing window — never saw the round being
   * scored, so it has nothing to pay out and must not hold back totals that
   * are, as far as it is concerned, simply the totals.
   */
  const [payingOut, setPayingOut] = useState(false)
  const animationId = useRef(0)
  const gateRef = useRef<OpenGate | null>(null)
  const slotsRelease = useRef<(() => void) | null>(null)

  /** Tells the server the batch is done, once nothing is still playing. */
  const ack = useCallback((gate: OpenGate) => {
    if (gate.acked || gate.holds > 0) return
    gate.acked = true
    if (gateRef.current === gate) gateRef.current = null
    send({ type: 'ANIM_DONE', gateId: gate.id })
  }, [])

  /**
   * A tab nobody is looking at holds nothing.
   *
   * The gate exists so an animation is *watched* — the server will not deal or
   * move the turn on until this client says the table has finished looking at
   * something. A backgrounded tab is not looking at anything, and worse, it
   * cannot say so: every hold releases on a `setTimeout`, which Chrome clamps
   * to a minute or so in a hidden tab and iOS suspends outright when the phone
   * locks. So the ack never went out, and the server waited the full
   * ANIMATION_GATE_MAX_MS — eight seconds — *for every batch*. Since the gate
   * falls back to the host when the owning seat has nothing to say, a host who
   * pockets their phone becomes the table's metronome at eight seconds a beat,
   * with the whole of each wait handed back to whoever is on the clock.
   *
   * Nothing is corrupted by letting go early: the state is already applied, and
   * a stale `ANIM_DONE` arriving afterwards is dropped by the server's own id
   * check. What is lost is an animation nobody was watching.
   */
  useEffect(() => {
    const onHidden = () => {
      if (!document.hidden) return
      const gate = gateRef.current
      if (!gate) return
      gate.holds = 0
      ack(gate)
    }
    document.addEventListener('visibilitychange', onHidden)
    return () => document.removeEventListener('visibilitychange', onHidden)
  }, [ack])

  /**
   * Registers one running animation against the open batch and returns the
   * release to call when it finishes. It releases itself after [ms] regardless,
   * so an animation that never reports back costs a beat rather than stalling
   * the table until the server's own timeout.
   */
  const hold = useCallback(
    (ms: number) => {
      const gate = gateRef.current
      if (!gate) return () => {}
      gate.holds += 1
      let released = false
      const release = () => {
        if (released) return
        released = true
        gate.holds -= 1
        ack(gate)
      }
      window.setTimeout(release, paced(ms))
      return release
    },
    [ack, paced],
  )

  const dismissAnimation = useCallback((type: GameAnimation['type'], id?: string) => {
    setAnimations((prev) => prev.filter((a) => (id ? a.id !== id : a.type !== type)))
  }, [])

  /**
   * [delayMs] holds the animation back until the card that set it off has
   * landed.
   *
   * The wait is added to the hold rather than absorbed into it. It used to be
   * absorbed, to keep the gate short — the server hands gated time back to
   * whoever is on the clock, so a longer gate quietly inflates their turn — but
   * what that bought was a table that moved on half a second before anything it
   * was waiting for had finished. The coin was taken away in the middle of its
   * last turn and the bottle before it had stopped, every single time. A beat
   * of somebody's clock is the cheaper thing to spend.
   */
  const pushAnimation = useCallback(
    (spec: AnimationSpec, delayMs = 0, ttlOverride?: number) => {
      const id = `anim-${++animationId.current}`
      // An override, not a second table: one animation in the game is longer or
      // shorter depending on something the server told us — a gambler card
      // nobody has seen has to be read rather than recognised — and the table
      // below is still where its two lengths are written down.
      const ttl = ttlOverride ?? ANIMATION_TTL_MS[spec.type]
      hold(delayMs + ttl)
      const show = () => {
        setAnimations((prev) => [...prev, { ...spec, id, ms: paced(ttl) } as GameAnimation])
        window.setTimeout(() => {
          setAnimations((prev) => prev.filter((a) => a.id !== id))
        }, paced(ttl))
      }
      if (delayMs > 0) window.setTimeout(show, paced(delayMs))
      else show()
    },
    [hold, paced],
  )

  /**
   * [drawn] is the card the seat took off the deck in this batch, or null. It is
   * what decides whether the bust gets its slam: a card that arrived any other
   * way has already been watched arriving, and carrying it up a second time
   * would say it happened twice.
   *
   * [delayMs] holds the whole thing back behind a played card, the same as
   * [pushAnimation] does — a bust set off by a card starts once that card has
   * landed, not while it is still in the air. It is only ever passed for a bust
   * that had no card of its own: a bust the seat *drew* is announced by the card
   * coming off the deck, and that card is dealt on the same beat every other
   * draw in the batch is, whatever else is in flight. Holding it back would also
   * mean the card sat in the fan for half a second before being taken back out
   * of it to be carried up, which reads as it arriving twice.
   */
  const startBust = useCallback(
    (event: Extract<GameEvent, { type: 'bust' }>, drawn: Card | null, delayMs = 0) => {
      // The two beats after the landing are the same length either way; what a
      // drawn card buys is everything before it.
      const lift = drawn ? BUST_LAND_MS : 0
      hold(delayMs + lift + BUST_REVEAL_MS + BUST_SCATTER_MS)

      const step = (at: number, next: () => void) => window.setTimeout(next, paced(delayMs + at))
      const open = () =>
        setBust(() => ({
          playerId: event.playerId,
          cardId: event.card?.id,
          matchedId: event.matched?.id,
          card: drawn,
          ms: paced(BUST_CARD_MS),
          phase: drawn ? ('hover' as const) : ('reveal' as const),
        }))
      if (delayMs > 0) window.setTimeout(open, paced(delayMs))
      else open()

      // The card comes down, and the hand it came down on is what everybody is
      // looking at from here.
      if (drawn) {
        step(lift, () =>
          setBust((prev) => (prev?.playerId === event.playerId ? { ...prev, phase: 'reveal' } : prev)),
        )
      }
      // The noise and the throw go with the landing rather than with the news.
      // A bust sounded over a card still in the air tells the whole table the
      // answer while it is watching the question — which is the thing
      // `PendingOutcome` exists on the other side of the wire to prevent.
      pushAnimation({ type: 'screenShake', strength: 'bust' }, delayMs + lift)
      playAfter(paced(delayMs + lift), 'bust')

      step(lift + BUST_REVEAL_MS, () =>
        setBust((prev) => (prev?.playerId === event.playerId ? { ...prev, phase: 'scatter' } : prev)),
      )
      // Paced, like the hold it has to agree with. Left in real milliseconds it
      // ran four times longer than the gate it was measured against whenever the
      // e2e suite turned the table down, so the server was told the hand had
      // settled while it was still in the air.
      step(lift + BUST_REVEAL_MS + BUST_SCATTER_MS, () =>
        setBust((prev) => (prev?.playerId === event.playerId ? null : prev)),
      )
    },
    [hold, paced, pushAnimation],
  )

  /**
   * Sends cards flying between seats. They are cleared by id rather than
   * wholesale, so a flight that starts while another is still in the air does
   * not cut the first one short.
   */
  const startFlights = useCallback(
    (moving: CardFlight[], delayMs = 0) => {
      if (moving.length === 0) return
      // The wait counts, the same as it does for an animation — see
      // [pushAnimation]. A card still crossing the table when the next one is
      // dealt is the thing this is here to stop.
      hold(delayMs + STEAL_MS)
      const show = () => {
        setFlights((current) => [...current, ...moving])
        window.setTimeout(() => {
          const ids = new Set(moving.map((flight) => flight.id))
          setFlights((current) => current.filter((flight) => !ids.has(flight.id)))
        }, paced(STEAL_MS))
      }
      if (delayMs > 0) window.setTimeout(show, paced(delayMs))
      else show()
    },
    [hold, paced],
  )

  // The slot machine is the one animation that reports its own end rather than
  // running to a fixed length, so it releases the table itself.
  const dismissSlots = useCallback(() => {
    setSlots(null)
    slotsRelease.current?.()
    slotsRelease.current = null
  }, [])

  /**
   * When the closing card is due, read off the state that came with the events
   * rather than out of the last render — the payout is scheduled backwards from
   * it, and by a render's worth of staleness it would be scheduled into a
   * window that has already begun.
   */
  const outroFromRef = useRef<number | null>(null)

  /**
   * Pays the table, one seat at a time.
   *
   * The round is scored in a single stroke on the server — every total changes
   * at once — and it used to arrive that way too, behind a closing card that
   * covers the felt. So the client spends the window the server reserved before
   * that card: each seat's points lift off the hand that made them, fly to that
   * player's line on the scoreboard, and the total moves only when they land.
   *
   * Smallest first, so the round's best hand is the last thing paid.
   */
  const startPayout = useCallback(
    (deltas: Record<string, number>, order: Player[]) => {
      if (order.length === 0) return
      const step = paced(PAYOUT_STEP_MS)
      // Backwards from the closing card: the server sized the window, and this
      // has to end inside it however long whatever ended the round took.
      const outroFrom = outroFromRef.current
      const reserved = paced(PAYOUT_LEAD_MS) + order.length * step + paced(PAYOUT_TAIL_MS)
      const start = outroFrom
        ? outroFrom - reserved + paced(PAYOUT_LEAD_MS)
        : Date.now() + paced(PAYOUT_LEAD_MS)

      setPaidIds([])
      setPayingOut(true)
      order.forEach((player, index) => {
        const at = Math.max(0, start + index * step - Date.now())
        window.setTimeout(() => {
          setAward({ id: `award-${player.id}-${index}`, playerId: player.id, points: deltas[player.id] ?? 0 })
          play('draw')
        }, at)
        // The total moves when the points get there, not when they set off.
        window.setTimeout(() => setPaidIds((paid) => [...paid, player.id]), at + paced(PAYOUT_FLIGHT_MS))
      })
      window.setTimeout(
        () => {
          setAward(null)
          setPayingOut(false)
        },
        Math.max(0, start + (order.length - 1) * step - Date.now()) + paced(PAYOUT_TAIL_MS),
      )
    },
    [paced],
  )

  /**
   * How long the rest of this batch waits for a played action card to come down
   * on its target. Set for the whole batch, because the card and everything it
   * sets off — the shake, the particles, the frost — arrive together.
   */
  const smashDelay = useRef(0)

  /**
   * What each seat took off the deck in this batch, keyed `playerId:cardId`.
   *
   * Read by the bust, which is shown one of two ways depending on where the card
   * that did it came from. Keyed on the seat as well as the card so a drawn card
   * handed straight on — see "redirect" — is not treated as one the seat it
   * busted drew for itself; that card has already been watched crossing the
   * table and carrying it up again would be showing the same trip twice. The
   * server asks the same question the same way in `closingWindowFor`.
   */
  const drawnThisBatch = useRef(new Map<string, Card>())

  /**
   * A player's name for an overlay to print. Kept as a callback over the
   * server's own list so a reveal names people rather than ids.
   */
  /**
   * What to print on a seat: what that player's cards are worth to them, which
   * is not always what they add up to — see the "antimatter" card. The server
   * works it out; an older one does not send it, and the plain total stands in.
   *
   * Null means *you are not being shown this* — see the "redacted" card. Told
   * apart from nought on purpose, and typed so it cannot be quietly printed as
   * one: a seat holding four cards and showing a zero is a lie, where a seat
   * showing a question mark is the card doing exactly what it says.
   */
  const worthOf = useCallback(
    (player: Player): number | null => {
      if ((state?.hiddenHandIds ?? []).includes(player.id)) return null
      return state?.handWorth?.[player.id] ?? player.handValue
    },
    [state],
  )

  /**
   * Whether a seat's round is over *and* worth nothing, which is what the strike
   * through a busted number means. Not the same question as "did they bust" —
   * see the "antimatter" card, whose holder busts and pays for it anyway. The
   * server names those seats; an older one does not, and every bust reads as
   * written off the way it always did.
   */
  const writtenOff = useCallback(
    (player: Player) =>
      player.status === 'bust' && !(state?.bustStillCountsIds ?? []).includes(player.id),
    [state],
  )

  const nameOf = useCallback(
    (playerId: string) => players.find((p) => p.id === playerId)?.name ?? playerId,
    [players],
  )

  /** Translates one server event into whatever the table should show for it. */
  const applyEvent = useCallback(
    (event: GameEvent) => {
      const delay = smashDelay.current
      switch (event.type) {
        case 'bust': {
          // The shake and the noise are the landing's, not the news's, so they
          // are scheduled inside the bust rather than fired here.
          const drew = event.card
            ? drawnThisBatch.current.get(`${event.playerId}:${event.card.id}`) ?? null
            : null
          startBust(event, drew, drew ? 0 : delay)
          break
        }
        case 'secondChance':
          pushAnimation(
            {
              type: 'secondLife',
              playerId: event.playerId,
              card: event.card,
              matched: event.matched,
              saver: event.saver,
            },
            delay,
          )
          // No sample of its own: the card is heard being played and then heard
          // landing on the one it tore, which between them is the shape of what
          // happened.
          play('actionCard')
          playAfter(paced(delay + SECOND_LIFE_RIP_MS), 'actionLanded')
          break
        case 'freeze':
          pushAnimation({ type: 'freeze', playerId: event.playerId }, delay)
          playAfter(delay, 'freeze')
          break
        case 'steal':
          startFlights(
            [{
              id: `steal-${event.card.id}`,
              fromPlayerId: event.fromPlayerId,
              toPlayerId: event.toPlayerId,
              card: event.card,
            }],
            delay,
          )
          break
        case 'cardsSwapped':
          // Both cards go at once, crossing over the middle of the table —
          // which is the whole read on what just happened.
          startFlights(
            [
              {
                id: `swap-${event.firstCard.id}`,
                fromPlayerId: event.firstPlayerId,
                toPlayerId: event.secondPlayerId,
                card: event.firstCard,
              },
              {
                id: `swap-${event.secondCard.id}`,
                fromPlayerId: event.secondPlayerId,
                toPlayerId: event.firstPlayerId,
                card: event.secondCard,
              },
            ],
            delay,
          )
          break
        case 'flip7':
          pushAnimation({ type: 'flip7', playerId: event.playerId })
          play('flip7')
          break
        case 'fizzled':
          pushAnimation({ type: 'fizzled', playerId: event.playerId, cardDefId: event.cardDefId })
          break
        case 'coinFlip':
          // The face is known up front, exactly as the slot machine's card is,
          // so the coin lands on it instead of the client guessing from the
          // bust or the ×2 that follows in the same batch.
          pushAnimation(
            { type: 'coinFlip', playerId: event.playerId, call: event.call, result: event.result },
            delay,
          )
          break
        case 'bottleSpin':
          pushAnimation({ type: 'bottleSpin', victimId: event.victimId }, delay)
          break
        case 'tableSpun':
          // Deliberately not delayed behind the played card. The hands have
          // already moved in the state that arrived with this event, so the
          // slide has to be set up on the same frame — start it a beat later
          // and the cards teleport to their new seats and only then animate.
          pushAnimation({ type: 'tableSpun', direction: event.direction, playerIds: event.playerIds })
          // ...and the sound goes with the hands rather than with the card.
          play('draw')
          break
        case 'throws': {
          const winner = event.challengerWon ? event.challengerId : null
          // Two of the same settles nothing and the server is already asking
          // them again, so this says "again" rather than "no comeback" — the
          // prompt to throw arrives right behind it, and a caption that read
          // like an ending would make the next question look like a bug.
          const drew = event.challengerThrow === event.leaderThrow
          pushAnimation({
            type: 'showdown',
            title: event.challengerWon ? 'comeback!' : drew ? 'again!' : 'no comeback',
            sides: [
              {
                name: nameOf(event.challengerId),
                label: event.challengerThrow,
                lost: !!winner && winner !== event.challengerId,
              },
              {
                name: nameOf(event.leaderId),
                label: event.leaderThrow,
                lost: event.challengerWon,
              },
            ],
            footnote: event.challengerWon
              ? 'the scores change hands'
              : drew
                ? 'a draw — throw again'
                : 'the leader holds on',
          }, delay)
          break
        }
        case 'allIn':
          pushAnimation({
            type: 'showdown',
            title: 'all in!',
            sides: Object.entries(event.bets).map(([playerId, card]) => ({
              name: nameOf(playerId),
              card,
              lost: event.halvedIds.includes(playerId),
            })),
            footnote: 'highest and lowest score half the round',
          }, delay)
          break
        case 'pointsTransferred':
          // Behind the played card, like everything else a card sets off: the
          // toll is charged by the card arriving, so the points leave the seat
          // once it has landed on them.
          pushAnimation(
            {
              type: 'pointsTransferred',
              fromPlayerId: event.fromPlayerId,
              toPlayerId: event.toPlayerId,
              points: event.points,
            },
            delay,
          )
          break
        case 'timeout':
          pushAnimation({ type: 'timeout', playerId: event.playerId })
          break
        case 'stay':
          play('goOut')
          break
        case 'gamblerPlayed':
          // Not delayed behind anything: this *is* the card being played, and
          // everything it sets off queues up behind it instead — see
          // GAMBLER_LAND_MS where the batch is read.
          pushAnimation(
            {
              type: 'gamblerPlayed',
              playerId: event.playerId,
              card: event.card,
              firstSeen: !!event.firstSeen,
            },
            0,
            event.firstSeen ? GAMBLER_REVEAL_FIRST_MS : undefined,
          )
          play('actionCard')
          break
        case 'gamblerDrawn':
          // Somebody picked one up. The card itself is only in the drawer's own
          // copy of this event, so there is nothing to show either way — the
          // count on the seat is what changed, and the state carries that.
          play('draw')
          break
        case 'redirected':
          pushAnimation(
            {
              type: 'redirected',
              fromPlayerId: event.fromPlayerId,
              toPlayerId: event.toPlayerId,
              card: event.card,
            },
            delay,
          )
          break
        case 'gamblerCountered':
          // Behind the counter's own reveal: the card that did this is still
          // being read, and striking out its victim in the same breath would
          // hand the table the answer and the question at once.
          pushAnimation(
            {
              type: 'countered',
              playerId: event.playerId,
              card: event.card,
              returned: !!event.returned,
            },
            delay,
          )
          break
        case 'gamblerReturned':
          // The count on the seat says it; there is nothing to watch.
          break
        case 'gamblerDeflected':
          pushAnimation({ type: 'impact', targetId: event.toPlayerId }, delay)
          break
        case 'deckShuffled':
          play('draw')
          break
        case 'roundScored': {
          play('roundEnded')
          // Smallest first: the round's best hand is the last thing paid, and a
          // seat that made nothing is still a seat that gets its moment.
          const deltas = event.deltas
          const order = [...players].sort((a, b) => (deltas[a.id] ?? 0) - (deltas[b.id] ?? 0))
          startPayout(deltas, order)
          break
        }
        case 'slots':
          // The server announces the card up front so the reels can land on it
          // and the machine can be gone before it is dealt.
          slotsRelease.current?.()
          slotsRelease.current = hold(SLOTS_MAX_MS)
          setSlots(() => ({ playerId: event.playerId, card: event.card ?? null }))
          break
        case 'draw':
          // The card the slot machine spun up — land the reels on it.
          setSlots((prev) => (prev && prev.playerId === event.playerId && !prev.card
            ? { ...prev, card: event.card }
            : prev))
          // An action card announces itself; everything else is just a card.
          play(event.card.kind === 'action' ? 'actionCard' : 'draw')
          break
        case 'actionPlayed':
          // Slots takes the whole screen for its own machine, so the card is
          // not also flown across the table for it.
          if (event.cardDefId !== 'slots') {
            pushAnimation({ type: 'smash', targetId: event.targetPlayerId, cardDefId: event.cardDefId })
            // ...and the card is heard where it lands rather than where it was
            // thrown from. Every card that does something visible — the coin,
            // the bottle, a showdown, a toll — arrives in the same batch as
            // this, so this is the one place any of them says so.
            playAfter(delay, 'actionLanded')
          }
          if (event.cardDefId === 'drawThree') {
            pushAnimation({ type: 'drawThree', playerId: event.targetPlayerId }, delay)
          } else if (event.cardDefId !== 'slots' && event.cardDefId !== 'freeze') {
            // Freeze and draw 3 have their own visuals; everything else gets
            // the generic slam.
            pushAnimation({ type: 'impact', targetId: event.targetPlayerId }, delay)
          }
          pushAnimation({ type: 'screenShake', strength: 'slam' }, delay)
          break
        default:
          break
      }
    },
    [pushAnimation, startBust, startFlights, hold, nameOf, paced, players, startPayout],
  )

  /**
   * Starts tracking the batch the server is holding the table on, if this
   * client is the one it is waiting for. An id already being tracked is left
   * alone — a clock-only state push must not reset the count and ack an
   * animation that is still running.
   */
  const openGate = useCallback((gate: AnimationGate | undefined, playerId: string | null) => {
    if (!gate || gate.ackPlayerId !== playerId) {
      gateRef.current = null
      return
    }
    if (gateRef.current?.id === gate.id) return
    gateRef.current = { id: gate.id, holds: 0, acked: false }
  }, [])

  // Subscribe to the socket's event stream rather than reading it back out of
  // render: these arrive from outside React, and reacting to them there is what
  // keeps a burst of events from cascading renders through the whole table.
  useEffect(
    () =>
      useGameStore.subscribe((next, previous) => {
        if (next.eventSeq === previous.eventSeq) return
        openGate(next.state?.animationGate, next.localPlayerId)
        outroFromRef.current = next.state?.roundOutroFrom ?? null
        const smashing = next.events.some((e) => e.type === 'actionPlayed' && e.cardDefId !== 'slots')
        // A gambler card turning over holds the middle of the table for longer
        // than a played card does, and whatever it sets off has to arrive after
        // it — the card is the announcement, and answering it before it has
        // been read is exactly what `PendingOutcome` exists to prevent.
        const revealing = next.events.some((e) => e.type === 'gamblerPlayed')
        smashDelay.current = revealing ? GAMBLER_LAND_MS : smashing ? SMASH_LAND_MS : 0
        // Read ahead of the loop rather than inside it: a bust arrives after the
        // draw that caused it, but the bust has to know about a draw that has
        // not been reached yet when a card is redirected and busts its receiver.
        drawnThisBatch.current = new Map(
          next.events.flatMap((e) =>
            e.type === 'draw' ? [[`${e.playerId}:${e.card.id}`, e.card] as const] : [],
          ),
        )
        for (const event of next.events) applyEvent(event)
        smashDelay.current = 0
        drawnThisBatch.current = new Map()
        // Nothing registered a hold, so there is nothing to watch: a batch this
        // client draws no animation for should not cost the table a pause.
        const gate = gateRef.current
        if (gate) ack(gate)
      }),
    [applyEvent, openGate, ack],
  )

  // ═══════════════════════════════════════════
  // Clocks — the server hands out absolute deadlines and the client just
  // renders against them, so animations cannot drift out of step with play.
  // ═══════════════════════════════════════════

  const deadline = state?.turnDeadline
  const introUntil = state?.roundIntroUntil
  const outroFrom = state?.roundOutroFrom
  const outroUntil = state?.roundOutroUntil
  const [clock, setClock] = useState(() => Date.now())

  const ticking = !!deadline || !!introUntil || !!outroUntil
  useEffect(() => {
    if (!ticking) return
    const interval = window.setInterval(() => setClock(Date.now()), 100)
    return () => window.clearInterval(interval)
  }, [ticking])

  const showRoundIntro = phase === 'PLAYING' && !!introUntil && clock < introUntil
  // Open-ended on purpose. App unmounts the whole table at `roundOutroUntil` and
  // puts the scoreboard up in its place, so there is nothing for the card to
  // hand back to — closing it here as well only meant a second clock crossing
  // the same deadline on its own tick, and a bare table for whichever gap fell
  // between them. `phase` is the backstop: the next round arrives as PLAYING and
  // takes the card with it.
  const showRoundOutro = phase !== 'PLAYING' && !!outroFrom && !!outroUntil && clock >= outroFrom

  const totalMs = (state?.config.turnTimeSeconds ?? 30) * 1000
  const timer: TurnTimer | null = deadline
    ? (() => {
        const remainingMs = Math.max(0, deadline - clock)
        return { remainingMs, totalMs, fraction: Math.min(1, remainingMs / totalMs) }
      })()
    : null

  // Nothing is on the clock while the round is still being dealt out.
  const isDealing = (state?.dealQueue.length ?? 0) > 0
  const closeFrom = Math.min(CLOCK_CLOSE_MS, totalMs * CLOCK_CLOSE_FRACTION)
  const clockIsClose = !!timer && !isDealing && timer.remainingMs <= closeFrom
  /** How far into the closing stretch the clock is, from 0 to 1. */
  const clockUrgency = clockIsClose && timer ? 1 - Math.min(1, timer.remainingMs / closeFrom) : 0

  // ═══════════════════════════════════════════
  // Action card targeting and decisions
  // ═══════════════════════════════════════════

  const pendingAction = state?.pendingAction
  const pendingDef: ActionCardInfo | undefined = findAction(catalog, pendingAction?.cardDefId)
  const isPickingTarget = !!pendingAction

  /**
   * Everybody the prompt is asking. One name for nearly every card; the ones
   * that ask the table at once name everybody. An older server sends nothing,
   * and the drawer was always the only answerer before this.
   */
  const advertisedResponders = pendingAction?.responders?.join(',') ?? ''
  const responders = useMemo(
    () => (advertisedResponders ? advertisedResponders.split(',') : []),
    [advertisedResponders],
  )
  const answeredBy = pendingAction?.answered ?? EMPTY_PICKS
  /** More than one player is being asked, so each is asked about their own cards. */
  const pendingIsShared = responders.length > 1

  /**
   * Whether the table is waiting on *me*. Not "did I draw it": a prompt that
   * asks the whole table is every bit as much mine to answer, and one I have
   * already answered is not mine any more however it started.
   */
  const pendingIsLocal =
    !!pendingAction &&
    !!localPlayerId &&
    (responders.length === 0 ? pendingAction.playerId === localPlayerId : responders.includes(localPlayerId)) &&
    !answeredBy.includes(localPlayerId)

  /** I have answered and the rest of the table has not. */
  const pendingAwaitingOthers =
    !!pendingAction && !!localPlayerId && answeredBy.includes(localPlayerId)
  // The server works out who a card may legally hit; the picker offers no
  // others. If it advertised nothing at all — a backend older than this build,
  // or a field that failed to arrive — fall back to every player still in the
  // round. The server validates the pick regardless, so the worst case is
  // offering one seat too many; refusing every seat would strand the round.
  const advertised = pendingAction?.validTargets
  const activeIds = players.filter((p) => p.status === 'active').map((p) => p.id).join(',')
  const validTargets = useMemo(
    () => (!pendingAction ? [] : advertised?.length ? advertised : activeIds ? activeIds.split(',') : []),
    [pendingAction, advertised, activeIds],
  )

  // The question the card asks its drawer, if it asks one. The pending action
  // carries it; the catalog's copy of the same card is the fallback for a
  // server that predates the field. Joined so the identity is stable across
  // pushes that changed nothing about the pick.
  const advertisedOptions = pendingAction?.options?.length
    ? pendingAction.options.join(',')
    : pendingDef?.options?.join(',') ?? ''
  const pendingOptions = useMemo(
    () => (pendingAction && advertisedOptions ? advertisedOptions.split(',') : []),
    [pendingAction, advertisedOptions],
  )
  /**
   * Whether the table is waiting on an answer rather than only a seat. Gated on
   * the options themselves, never on how many seats are offered: a self-target
   * card that asks a question advertises the drawer as its one valid target, so
   * counting seats would say "nothing to pick" for exactly the cards that have
   * the most to ask.
   */
  const needsChoice = pendingOptions.length > 0

  /**
   * Whether the card is pointed at cards on the table rather than at a seat.
   * The seats stay out of it entirely — the card resolves on its drawer, so the
   * one "target" it advertises is a formality the client never has to click.
   */
  /**
   * Whether the table stopped for a question something set up earlier rather
   * than for a card that was just drawn — a bomb going off. Nothing arrived to
   * explain it, so the prompt has to say what it is on its own.
   */
  const pendingPhase = pendingAction?.phase ?? 'play'
  const pendingIsDeferred = !!pendingAction && pendingPhase !== 'play'

  const picksCards = pendingAction?.kind === 'card'

  /**
   * Whether the pick is made from the deck rather than from the table — a card
   * nobody is holding yet, with a price on it. The offers arrive priced and
   * pre-filtered to what this player can afford, so the sheet has no rules of
   * its own to keep.
   */
  const picksFromCatalog = pendingAction?.kind === 'catalog'
  const offers = pendingAction?.offers ?? EMPTY_OFFERS
  /** What the round has left this player to spend, which is what the shop shows. */
  const purse = (me?.score ?? 0) + (state?.roundAdjustments?.[localPlayerId ?? ''] ?? 0)
  const advertisedCards = pendingAction?.validCards?.join(',') ?? ''
  const validCards = useMemo(
    () => (picksCards && advertisedCards ? advertisedCards.split(',') : []),
    [picksCards, advertisedCards],
  )
  const picksNeeded = picksCards ? pendingAction?.picks ?? 1 : 0
  /**
   * Whether each pick has to come off a different seat. The server's answer;
   * a server too old to have one only ever asked for trades, which is what
   * this rule was written for.
   */
  const oneCardPerSeat = pendingAction?.oneCardPerSeat ?? true

  /** Who is holding each card that may be picked. */
  const cardOwners = useMemo(() => {
    const owners = new Map<string, string>()
    if (!picksCards) return owners
    for (const player of players) {
      for (const card of [...player.hand, ...player.passives]) owners.set(card.id, player.id)
    }
    return owners
  }, [picksCards, players])

  /**
   * ...and with one seat there is nothing to click, so the option picker — or
   * the cards themselves — is the whole answer and the seats are not offered.
   */
  const seatIsImplied = (needsChoice || picksCards || picksFromCatalog) && validTargets.length <= 1

  // Keyed on the physical card, not its type: two strikes in one round share a
  // cardDefId, and keying on that left the second one permanently "already
  // picked" so no seat could be clicked.
  const pendingKey = pendingAction
    ? pendingAction.cardId ?? `${pendingAction.playerId}:${pendingAction.cardDefId}`
    : null
  /**
   * What this client has answered for the card on the table: a seat, an option,
   * or both. Every pick replaces it wholesale, so the object's identity is what
   * [sentAnswer] recognises.
   */
  const [answer, setAnswer] = useState<{
    key: string
    targetId: string | null
    option: string | null
    cards: string[]
  } | null>(null)
  /**
   * The answer already handed to the server, so it goes over exactly once.
   * Held by identity rather than by key: a game is played with one deck, and
   * the same physical coin flip comes round again after a reshuffle — a key
   * remembered from last time would swallow the second answer entirely.
   */
  const sentAnswer = useRef<object | null>(null)
  const answered = answer && answer.key === pendingKey ? answer : null
  const targetChosen = answered?.targetId ?? null
  const optionChosen = answered?.option ?? null
  const cardsChosen = answered?.cards ?? EMPTY_PICKS

  const animating = !!state?.animationGate

  const pickTarget = useCallback(
    (targetId: string) => {
      if (!pendingAction || !pendingKey || !pendingIsLocal) return
      if (answer?.key === pendingKey) return
      if (!validTargets.includes(targetId)) return
      // A card that also asks a question is not answered yet — the send waits
      // below for the option. Everything else is complete as it stands.
      setAnswer({ key: pendingKey, targetId, option: null, cards: EMPTY_PICKS })
    },
    [pendingAction, pendingKey, pendingIsLocal, answer, validTargets],
  )

  /**
   * Whether this card is one the prompt on the table can still be pointed at.
   * The rule lives here rather than in the board so that what is clickable and
   * what a click does can never disagree.
   */
  const canPickCard = useCallback(
    (cardId: string) => {
      if (!picksCards || !pendingIsLocal) return false
      if (!validCards.includes(cardId)) return false
      // A prompt that asks several players at once asks each of them about
      // their own cards; the list on the wire is everybody's.
      if (pendingIsShared && cardOwners.get(cardId) !== localPlayerId) return false
      if (cardsChosen.length >= picksNeeded || cardsChosen.includes(cardId)) return false
      // Two cards off one seat would trade a hand with itself. The server
      // replaces such a pair rather than refusing it, but offering it at all
      // would let a player throw the card away without meaning to.
      //
      // Whether that rule is on is the *prompt's* to say, not this client's: a
      // card that hands your own cards out takes every pick off one seat by
      // definition — see the "circlejerk" card — and a rule kept here would
      // refuse the second one for ever.
      if (!oneCardPerSeat) return true
      const owner = cardOwners.get(cardId)
      return !(owner && cardsChosen.some((id) => cardOwners.get(id) === owner))
    },
    [
      picksCards, pendingIsLocal, validCards, cardsChosen, picksNeeded,
      cardOwners, oneCardPerSeat, pendingIsShared, localPlayerId,
    ],
  )

  /**
   * Whether clicking this card would take it back out of the answer.
   *
   * Only while the answer is still short of what the card asked for: the last
   * click completes it and sends it, and a pick that has gone to the server is
   * not this client's to change. Everything up to that point is, though — a
   * two-card pick is two decisions, and being unable to undo the first one
   * meant a misclick spent the card.
   */
  const canUnpickCard = useCallback(
    (cardId: string) =>
      picksCards && pendingIsLocal && cardsChosen.includes(cardId) && cardsChosen.length < picksNeeded,
    [picksCards, pendingIsLocal, cardsChosen, picksNeeded],
  )

  /**
   * Adds one card to a card-picking answer, or takes it back out — see
   * [canUnpickCard]. Unlike a seat, which is answered in a single click, this
   * builds up over several, so it is the only pick that may replace an answer
   * already latched for this card. It goes out below once the card has as many
   * as it asked for.
   */
  const pickCard = useCallback(
    (cardId: string) => {
      if (!pendingAction || !pendingKey || !pendingIsLocal) return
      const current = answer?.key === pendingKey ? answer : null
      const already = current?.cards ?? EMPTY_PICKS
      const unpicking = canUnpickCard(cardId)
      if (!unpicking && !canPickCard(cardId)) return
      setAnswer({
        key: pendingKey,
        // The card resolves on its drawer; the seat is a formality.
        targetId: current?.targetId ?? validTargets[0] ?? localPlayerId,
        option: current?.option ?? null,
        cards: unpicking ? already.filter((id) => id !== cardId) : [...already, cardId],
      })
    },
    [pendingAction, pendingKey, pendingIsLocal, canPickCard, canUnpickCard, answer, validTargets, localPlayerId],
  )

  /**
   * Buys one of the offers. The same shape as every other pick: latched here
   * and handed over below once the table is free, so a click that lands while
   * an animation is running is not thrown away.
   */
  const pickOffer = useCallback(
    (offerId: string) => {
      if (!pendingAction || !pendingKey || !pendingIsLocal || !picksFromCatalog) return
      if (answer?.key === pendingKey) return
      if (!offers.some((offer) => offer.id === offerId)) return
      setAnswer({
        key: pendingKey,
        targetId: validTargets[0] ?? localPlayerId,
        option: null,
        cards: [offerId],
      })
    },
    [pendingAction, pendingKey, pendingIsLocal, picksFromCatalog, offers, answer, validTargets, localPlayerId],
  )

  const pickOption = useCallback(
    (option: string) => {
      if (!pendingAction || !pendingKey || !pendingIsLocal) return
      if (!pendingOptions.includes(option)) return
      const current = answer?.key === pendingKey ? answer : null
      if (current?.option) return
      // The seat comes from the click that preceded this one, or — for a card
      // pointed at nobody but its drawer — is the only seat there ever was.
      const targetId = current?.targetId ?? (validTargets.length === 1 ? validTargets[0] : null)
      if (!targetId) return
      setAnswer({ key: pendingKey, targetId, option, cards: current?.cards ?? EMPTY_PICKS })
    },
    [pendingAction, pendingKey, pendingIsLocal, pendingOptions, answer, validTargets],
  )

  /**
   * Hands the answer over once the table is free.
   *
   * The server drops moves made while an animation is running, and the card
   * that asks the question arrives in the same push as the animation of it
   * being drawn — so sending on the click would throw the answer away and leave
   * the picker looking spent for a card that was never played. It is latched
   * locally instead and goes out the moment the gate lifts. A pick whose card
   * has since gone (a timeout, someone else's round ending) no longer matches
   * `pendingKey` and is simply dropped.
   */
  useEffect(() => {
    if (animating || !answer || sentAnswer.current === answer) return
    if (!pendingAction || answer.key !== pendingKey || !pendingIsLocal) return
    if (!answer.targetId || (needsChoice && !answer.option)) return
    // Half a pair of cards is not an answer; the rest of the clicks are coming.
    if (picksCards && answer.cards.length < picksNeeded) return
    if (picksFromCatalog && answer.cards.length === 0) return
    sentAnswer.current = answer
    send({
      type: 'PLAY_ACTION',
      targetPlayerId: answer.targetId,
      cardDefId: pendingAction.cardDefId,
      choice: answer.option ?? undefined,
      cards: answer.cards.length ? answer.cards : undefined,
    })
  }, [animating, answer, pendingAction, pendingKey, pendingIsLocal, needsChoice, picksCards, picksNeeded, picksFromCatalog])

  // ═══════════════════════════════════════════
  // The hidden hand — rolling rules
  // ═══════════════════════════════════════════

  const gamblerHand = state?.myGamblers ?? EMPTY_CARDS
  const playableIds = state?.playableGamblers
  const playableGamblers = useMemo(
    () => playableIds ?? EMPTY_PICKS,
    [playableIds],
  )
  const gamblerSlots = state?.gamblerLimits?.[localPlayerId ?? ''] ?? gamblerHand.length
  const isRollingRules = modeOf(state?.config) === 'rollingRules'

  /**
   * A gambler card offered but not yet sent.
   *
   * The same latch the pickers use, and for the same reason: the server drops a
   * move made while the table is animating, and the batch that made you want to
   * play a card arrives *with* the animation of it. Sending on the click would
   * throw away exactly the clicks people mean most.
   */
  const [offered, setOffered] = useState<{ cardId: string } | null>(null)
  /** Held by identity, not by id — a card comes round again after a reshuffle. */
  const sentOffer = useRef<object | null>(null)
  /**
   * The latch, but only while the card is still in hand.
   *
   * Derived rather than cleared. A card that has left the hand was played, and
   * an effect that reached back to tidy the latch up would be exactly the
   * cascading `setState` the lint forbids — the same reason `answered` above is
   * `answer` filtered by the prompt it belongs to rather than a second state.
   */
  const offeredCard = offered && gamblerHand.some((c) => c.id === offered.cardId) ? offered : null

  const canPlayGambler = useCallback(
    (cardId: string) => playableGamblers.includes(cardId),
    [playableGamblers],
  )

  const playGambler = useCallback(
    (cardId: string) => {
      if (!playableGamblers.includes(cardId)) return
      // Clicking the same card twice is not two plays. A *different* card does
      // replace the latch, so an offer that never became legal — the turn moved
      // on while the table was animating — cannot sit there blocking the rest
      // of the hand.
      if (offeredCard?.cardId === cardId) return
      setOffered({ cardId })
    },
    [playableGamblers, offeredCard],
  )

  useEffect(() => {
    if (!offeredCard || animating || sentOffer.current === offeredCard) return
    // It stopped being legal while we waited. A moment that passed is not an
    // error; the card is simply still in hand.
    if (!playableGamblers.includes(offeredCard.cardId)) return
    sentOffer.current = offeredCard
    send({ type: 'PLAY_GAMBLER', cardId: offeredCard.cardId })
  }, [animating, offeredCard, playableGamblers])

  // ─── Answering a card with a card, or with nothing ───

  const responseStack = state?.responseStack ?? EMPTY_FRAMES
  const responseWindow = state?.responseWindow
  const responseAwaiting = responseWindow?.awaiting ?? EMPTY_PICKS
  /**
   * Whether the table is asking *this* player whether to answer the card in
   * flight. Everybody still in is asked whenever a window opens at all, so this
   * being true says nothing about what anybody is holding — including you.
   */
  const canPass = !!responseWindow && !!localPlayerId && responseAwaiting.includes(localPlayerId)
  /** How many people the table is still waiting on, for the copy. */
  const responseWaiting = responseAwaiting.length

  /**
   * "Let it stand", latched like every other answer.
   *
   * Keyed on the frame rather than held as a bare flag: a window that shuts and
   * another that opens in the same breath — a counter answering a counter — are
   * two different questions, and a pass meant for the first must not be spent
   * on the second.
   */
  const [passed, setPassed] = useState<number | null>(null)
  const sentPass = useRef<number | null>(null)
  const passedThis = passed !== null && passed === responseWindow?.frameId

  const pass = useCallback(() => {
    if (!canPass || !responseWindow) return
    setPassed(responseWindow.frameId)
  }, [canPass, responseWindow])

  useEffect(() => {
    if (!canPass || animating) return
    const frameId = responseWindow?.frameId
    if (frameId === undefined || passed !== frameId || sentPass.current === frameId) return
    sentPass.current = frameId
    send({ type: 'PASS' })
  }, [animating, canPass, passed, responseWindow])

  // ═══════════════════════════════════════════
  // Player actions
  // ═══════════════════════════════════════════

  // An animation the table is held on counts as an interruption: the server
  // will refuse the move anyway, and a button that looks live but does nothing
  // is worse than one that is plainly out. So does a card that has landed and
  // not yet gone off — the gate covers nearly all of that wait, but not the
  // beat between this client saying the animation is done and the server
  // stepping, and a button that flickers back for one frame is a button
  // somebody will click.
  const isSettling = (state?.pendingOutcomes?.length ?? 0) > 0
  // A card in flight stops the table for everybody, including whoever is on
  // turn — the engine refuses their move while it is up. Leaving it out of here
  // left the hit and stay buttons lit through a whole response window, which is
  // precisely the button that looks live and does nothing.
  const isAnswering = (state?.responseStack?.length ?? 0) > 0
  const isInterrupted =
    isPickingTarget || isSettling || isAnswering || !!state?.forcedDraws || isDealing || animating
  const isMyTurn =
    phase === 'PLAYING' && !isInterrupted && currentPlayer?.id === localPlayerId && me?.status === 'active'
  const isEliminated = me?.status === 'bust' || me?.status === 'stayed'
  /**
   * Whether the "go out" button is out. Two reasons for it: an empty hand,
   * which is the opening rule, and a card that says its holder may not stop —
   * and the second of those is the server's answer rather than this client's,
   * because it is a rule and rules do not live here.
   */
  const cannotStay = !!localPlayerId && (state?.cannotStayIds?.includes(localPlayerId) ?? false)
  const mustDraw = ((me?.hand.length ?? 0) === 0 && (me?.passives.length ?? 0) === 0) || cannotStay

  const hit = useCallback(() => send({ type: 'HIT' }), [])
  const stay = useCallback(() => send({ type: 'STAY' }), [])

  /**
   * Whether the clock that is running is mine to answer — the same question the
   * server asks when it decides whether to start one at all. A prompt puts
   * whoever it is waiting on under it; otherwise it is whoever is on turn.
   */
  const clockIsMine = !!deadline && (
    pendingAction ? pendingIsLocal : phase === 'PLAYING' && currentPlayer?.id === localPlayerId
  )

  // ...and if it is, it is worth hearing. Once per turn — see the gap above.
  const warnedAt = useRef(0)
  useEffect(() => {
    if (!clockIsMine || !clockIsClose) return
    if (Date.now() - warnedAt.current < CLOCK_WARNING_GAP_MS) return
    warnedAt.current = Date.now()
    play('timerRunningOut')
  }, [clockIsMine, clockIsClose])

  return {
    state,
    players,
    me,
    meIdx,
    others,
    currentPlayer,
    turnIndex,
    round,
    phase,
    roundStartPlayer: state?.roundStartPlayer ?? 0,
    deckCount: state?.deckCount ?? 0,
    discardCount: state?.discardCount ?? 0,
    localPlayerId,

    worthOf,
    writtenOff,

    // ─── The hidden hand ───
    isRollingRules,
    /** Your own gambler cards, face up. Nobody else's ever arrive. */
    gamblerHand,
    /** How many you may hold — five, plus room a "pouch" made. */
    gamblerSlots,
    /** How many each seat is carrying. Public; the faces are not. */
    gamblerCounts: state?.gamblerCounts,
    /** Whether the server says this card may be played right now. */
    canPlayGambler,
    playGambler,
    /** The card whose click is latched, waiting for the table to settle. */
    offeredGambler: offeredCard?.cardId ?? null,

    // ─── Cards answering cards ───
    /** Everything in flight, oldest first. The pile the table is reading. */
    responseStack,
    /** Whether the table is asking this player whether to answer it. */
    canPass,
    /** ...and whether they have said so and are waiting for it to go over. */
    passedThis,
    /** How many people the window is still waiting on. */
    responseWaiting,
    pass,

    isMyTurn,
    isEliminated,
    mustDraw,
    /** ...and whether it is a card saying so rather than an empty hand. */
    cannotStay,
    isDealing,
    animating,
    dealingPlayerId: state?.dealQueue[0] ?? null,
    forcedDraws: state?.forcedDraws,

    pendingAction,
    pendingDef,
    isPickingTarget,
    pendingIsLocal,
    pendingIsShared,
    pendingAwaitingOthers,
    responders,
    answeredBy,
    validTargets,
    picksCards,
    picksFromCatalog,
    offers,
    purse,
    pickOffer,
    pendingPhase,
    pendingIsDeferred,
    validCards,
    picksNeeded,
    cardsChosen,
    canPickCard,
    canUnpickCard,
    pickCard,
    targetChosen,
    pickTarget,
    pendingOptions,
    needsChoice,
    /** True when the seats are not part of the answer — the option picker is. */
    seatIsImplied,
    optionChosen,
    pickOption,

    hit,
    stay,

    /** The seat being paid right now, and what its round made. */
    award,
    /**
     * What a player's line on the scoreboard should read while the table is
     * being paid: the total they came into the round with until their own
     * points have landed on it. The server banks every score the moment the
     * round is scored, so this is the only place that difference lives.
     */
    totalOf: useCallback(
      (player: Player) => {
        if (!payingOut || paidIds.includes(player.id)) return player.score
        return player.score - (state?.roundDeltas[player.id] ?? 0)
      },
      [payingOut, paidIds, state],
    ),

    animations,
    dismissAnimation,
    bust,
    flights,
    slots,
    dismissSlots,
    showRoundIntro,
    showRoundOutro,
    introUntil,
    outroUntil,
    timer,
    /** The clock is into its closing stretch — see [CLOCK_CLOSE_MS]. */
    clockIsClose,
    clockUrgency,
  }
}

export type UseGameReturn = ReturnType<typeof useGame>
