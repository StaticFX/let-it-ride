import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore, findAction } from '../state/gameStore'
import { send } from '../net/client'
import { play } from '../audio/sfx'
import type { SoundName } from '../audio/sfx'
import type { ActionCardInfo, AnimationGate, Card, GameEvent, Offer, Player } from '../game/types'

// ─── Animations ───

export type GameAnimation =
  /**
   * How hard to throw the viewport. A `slam` is an action card landing on
   * someone; a `bust` is the round ending under a player, and gets the harder
   * of the two — see `.shake` / `.shake-bust`.
   */
  | { type: 'screenShake'; id: string; strength: 'slam' | 'bust' }
  | { type: 'impact'; id: string; targetId: string }
  | { type: 'smash'; id: string; targetId: string; cardDefId: string }
  | { type: 'freeze'; id: string; playerId: string }
  | { type: 'drawThree'; id: string; playerId: string }
  | { type: 'flip7'; id: string; playerId: string }
  | { type: 'timeout'; id: string; playerId: string }
  | { type: 'fizzled'; id: string; playerId: string; cardDefId: string }
  | { type: 'secondChance'; id: string; playerId: string }
  /** The coin lands on `result`; `call` is what the player said before it flew. */
  | { type: 'coinFlip'; id: string; playerId: string; call: string; result: string }
  /** The bottle stops pointing at `victimId` — the server picked, never the client. */
  | { type: 'bottleSpin'; id: string; victimId: string }
  /** Every hand in `playerIds` slid one seat; see [SpunHand] for the slide itself. */
  | { type: 'tableSpun'; id: string; direction: string; playerIds: string[] }
  /**
   * Everything answered in secret, turned over at once — the comeback's two
   * throws or the all in's whole table of bets. See [Showdown].
   */
  | {
      type: 'showdown'
      id: string
      title: string
      sides: { name: string; label?: string; card?: Card; lost?: boolean }[]
      footnote?: string
    }

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

type AnimationSpec = DistributiveOmit<GameAnimation, 'id'>

/**
 * A played action card flies from the table over the seat it was pointed at,
 * hangs there for a beat, and comes down on it.
 */
export const SMASH_MS = 900
/** How far into that flight the card actually lands. Keep in step with `targetSmash`. */
export const SMASH_LAND_MS = 560

/**
 * How long each animation stays on screen before it clears itself — and, since
 * every one of these registers a [hold], how long the table is held on it. Two
 * ceilings apply to anything added here:
 *
 * - the server gives up on a gate after ANIMATION_GATE_MAX_MS (5000ms), and
 *   gated time is handed back to whoever is on the clock, so a long animation
 *   quietly inflates their turn;
 * - a card that can end the round is not gated at all — the round is over — and
 *   only has OUTRO_AFTER_BUST_MS (2200ms) before the closing card covers the
 *   table. The coin and the bottle can both end a round, so both are cut to fit
 *   inside that window even after the played card has landed (SMASH_LAND_MS).
 */
const ANIMATION_TTL_MS: Record<GameAnimation['type'], number> = {
  screenShake: 600,
  impact: 900,
  smash: SMASH_MS,
  freeze: 1800,
  drawThree: 1400,
  flip7: 3200,
  timeout: 1500,
  fizzled: 1600,
  secondChance: 1800,
  coinFlip: 2000,
  bottleSpin: 1700,
  // The hands slide for TABLE_SPIN_SLIDE_MS; the rest is the swirl clearing off.
  tableSpun: 1000,
  // Long enough to read four names and their cards, and no longer — it holds
  // the table while it is up.
  showdown: 2600,
}

/**
 * One shared empty list for "no cards picked yet". A fresh `[]` every render
 * would change identity every render, and everything downstream of a pick is
 * memoised on it.
 */
const EMPTY_PICKS: string[] = []

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
 * A bust plays in two beats: first the pair that clashed is called out, then the
 * hand scatters. `card` and `matched` come straight from the server, so the
 * table can point at the exact two cards rather than just flashing red.
 */
export interface BustAnimation {
  playerId: string
  cardId?: string
  matchedId?: string
  phase: 'reveal' | 'scatter'
}

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
   * The wait is absorbed rather than added: the batch is held for the
   * animation's own length, exactly as it was before the card was given a
   * flight to make. Holding for the wait *plus* the animation would stretch
   * every action card's gate by half a second — and the server hands gated
   * time back to whoever is on the clock, so a longer gate quietly inflates
   * their turn. The smash is already holding the table across the wait, and
   * what runs past the gate is a particle burst rather than anything the next
   * move can land on top of.
   */
  const pushAnimation = useCallback(
    (spec: AnimationSpec, delayMs = 0) => {
      const id = `anim-${++animationId.current}`
      const ttl = ANIMATION_TTL_MS[spec.type]
      hold(ttl)
      const show = () => {
        setAnimations((prev) => [...prev, { ...spec, id } as GameAnimation])
        window.setTimeout(() => {
          setAnimations((prev) => prev.filter((a) => a.id !== id))
        }, paced(ttl))
      }
      if (delayMs > 0) window.setTimeout(show, delayMs)
      else show()
    },
    [hold, paced],
  )

  const startBust = useCallback(
    (event: Extract<GameEvent, { type: 'bust' }>) => {
      hold(BUST_REVEAL_MS + BUST_SCATTER_MS)
      setBust(() => ({
        playerId: event.playerId,
        cardId: event.card?.id,
        matchedId: event.matched?.id,
        phase: 'reveal' as const,
      }))
      window.setTimeout(() => {
        setBust((prev) => (prev?.playerId === event.playerId ? { ...prev, phase: 'scatter' } : prev))
      }, BUST_REVEAL_MS)
      window.setTimeout(() => {
        setBust((prev) => (prev?.playerId === event.playerId ? null : prev))
      }, BUST_REVEAL_MS + BUST_SCATTER_MS)
    },
    [hold],
  )

  /**
   * Sends cards flying between seats. They are cleared by id rather than
   * wholesale, so a flight that starts while another is still in the air does
   * not cut the first one short.
   */
  const startFlights = useCallback(
    (moving: CardFlight[], delayMs = 0) => {
      if (moving.length === 0) return
      // Absorbed, not added — see [pushAnimation].
      hold(STEAL_MS)
      const show = () => {
        setFlights((current) => [...current, ...moving])
        window.setTimeout(() => {
          const ids = new Set(moving.map((flight) => flight.id))
          setFlights((current) => current.filter((flight) => !ids.has(flight.id)))
        }, STEAL_MS)
      }
      if (delayMs > 0) window.setTimeout(show, delayMs)
      else show()
    },
    [hold],
  )

  // The slot machine is the one animation that reports its own end rather than
  // running to a fixed length, so it releases the table itself.
  const dismissSlots = useCallback(() => {
    setSlots(null)
    slotsRelease.current?.()
    slotsRelease.current = null
  }, [])

  /**
   * How long the rest of this batch waits for a played action card to come down
   * on its target. Set for the whole batch, because the card and everything it
   * sets off — the shake, the particles, the frost — arrive together.
   */
  const smashDelay = useRef(0)

  /**
   * A player's name for an overlay to print. Kept as a callback over the
   * server's own list so a reveal names people rather than ids.
   */
  const nameOf = useCallback(
    (playerId: string) => players.find((p) => p.id === playerId)?.name ?? playerId,
    [players],
  )

  /** Translates one server event into whatever the table should show for it. */
  const applyEvent = useCallback(
    (event: GameEvent) => {
      const delay = smashDelay.current
      switch (event.type) {
        case 'bust':
          startBust(event)
          pushAnimation({ type: 'screenShake', strength: 'bust' })
          play('bust')
          break
        case 'secondChance':
          pushAnimation({ type: 'secondChance', playerId: event.playerId })
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
          playAfter(delay, 'actionCard')
          break
        case 'bottleSpin':
          pushAnimation({ type: 'bottleSpin', victimId: event.victimId }, delay)
          playAfter(delay, 'actionCard')
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
          pushAnimation({
            type: 'showdown',
            title: event.challengerWon ? 'comeback!' : 'no comeback',
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
              : event.challengerThrow === event.leaderThrow
                ? 'a draw — nothing moves'
                : 'the leader holds on',
          }, delay)
          playAfter(delay, 'actionCard')
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
          playAfter(delay, 'actionCard')
          break
        case 'timeout':
          pushAnimation({ type: 'timeout', playerId: event.playerId })
          break
        case 'stay':
          play('goOut')
          break
        case 'roundScored':
          play('roundEnded')
          break
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
    [pushAnimation, startBust, startFlights, hold, nameOf],
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
        const smashing = next.events.some((e) => e.type === 'actionPlayed' && e.cardDefId !== 'slots')
        smashDelay.current = smashing ? SMASH_LAND_MS : 0
        for (const event of next.events) applyEvent(event)
        smashDelay.current = 0
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
      const owner = cardOwners.get(cardId)
      return !(owner && cardsChosen.some((id) => cardOwners.get(id) === owner))
    },
    [picksCards, pendingIsLocal, validCards, cardsChosen, picksNeeded, cardOwners, pendingIsShared, localPlayerId],
  )

  /**
   * Adds one card to a card-picking answer. Unlike a seat, which is answered in
   * a single click, this builds up over several — so it is the only pick that
   * may replace an answer already latched for this card, and it goes out below
   * once the card has as many as it asked for.
   */
  const pickCard = useCallback(
    (cardId: string) => {
      if (!pendingAction || !pendingKey || !pendingIsLocal) return
      if (!canPickCard(cardId)) return
      const current = answer?.key === pendingKey ? answer : null
      const already = current?.cards ?? EMPTY_PICKS
      setAnswer({
        key: pendingKey,
        // The card resolves on its drawer; the seat is a formality.
        targetId: current?.targetId ?? validTargets[0] ?? localPlayerId,
        option: current?.option ?? null,
        cards: [...already, cardId],
      })
    },
    [pendingAction, pendingKey, pendingIsLocal, canPickCard, answer, validTargets, localPlayerId],
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
  // Player actions
  // ═══════════════════════════════════════════

  const isDealing = (state?.dealQueue.length ?? 0) > 0
  // An animation the table is held on counts as an interruption: the server
  // will refuse the move anyway, and a button that looks live but does nothing
  // is worse than one that is plainly out.
  const isInterrupted = isPickingTarget || !!state?.forcedDraws || isDealing || animating
  const isMyTurn =
    phase === 'PLAYING' && !isInterrupted && currentPlayer?.id === localPlayerId && me?.status === 'active'
  const isEliminated = me?.status === 'bust' || me?.status === 'stayed'
  const mustDraw = (me?.hand.length ?? 0) === 0 && (me?.passives.length ?? 0) === 0

  const hit = useCallback(() => send({ type: 'HIT' }), [])
  const stay = useCallback(() => send({ type: 'STAY' }), [])

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

    isMyTurn,
    isEliminated,
    mustDraw,
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
  }
}

export type UseGameReturn = ReturnType<typeof useGame>
