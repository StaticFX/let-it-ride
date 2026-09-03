import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore, findAction } from '../state/gameStore'
import { send } from '../net/client'
import { play } from '../audio/sfx'
import type { SoundName } from '../audio/sfx'
import type { ActionCardInfo, AnimationGate, Card, GameEvent, Player } from '../game/types'

// ─── Animations ───

export type GameAnimation =
  | { type: 'screenShake'; id: string }
  | { type: 'impact'; id: string; targetId: string }
  | { type: 'smash'; id: string; targetId: string; cardDefId: string }
  | { type: 'freeze'; id: string; playerId: string }
  | { type: 'drawThree'; id: string; playerId: string }
  | { type: 'flip7'; id: string; playerId: string }
  | { type: 'timeout'; id: string; playerId: string }
  | { type: 'fizzled'; id: string; playerId: string; cardDefId: string }
  | { type: 'secondChance'; id: string; playerId: string }

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

type AnimationSpec = DistributiveOmit<GameAnimation, 'id'>

/**
 * A played action card flies from the table over the seat it was pointed at,
 * hangs there for a beat, and comes down on it.
 */
export const SMASH_MS = 900
/** How far into that flight the card actually lands. Keep in step with `targetSmash`. */
export const SMASH_LAND_MS = 560

/** How long each animation stays on screen before it clears itself. */
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
}

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

/** A card visibly moving from one seat to another. */
export interface StealAnimation {
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

  const players: Player[] = state?.players ?? []
  const round = state?.round ?? 0
  const phase = state?.phase ?? 'LOBBY'
  const turnIndex = state?.turnIndex ?? 0

  const me = players.find((p) => p.id === localPlayerId)
  const meIdx = players.findIndex((p) => p.id === localPlayerId)
  const others = players.filter((p) => p.id !== localPlayerId)
  const currentPlayer = players[turnIndex]

  // ═══════════════════════════════════════════
  // Animation state — everything here is driven by server events
  // ═══════════════════════════════════════════

  const [animations, setAnimations] = useState<GameAnimation[]>([])
  const [bust, setBust] = useState<BustAnimation | null>(null)
  const [steal, setSteal] = useState<StealAnimation | null>(null)
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
      window.setTimeout(release, ms)
      return release
    },
    [ack],
  )

  const dismissAnimation = useCallback((type: GameAnimation['type'], id?: string) => {
    setAnimations((prev) => prev.filter((a) => (id ? a.id !== id : a.type !== type)))
  }, [])

  /**
   * [delayMs] holds the animation back without letting go of the table: the
   * batch stays gated for the wait plus the animation, so an effect that is
   * queued behind a card still in the air cannot be overtaken by the next move.
   */
  const pushAnimation = useCallback(
    (spec: AnimationSpec, delayMs = 0) => {
      const id = `anim-${++animationId.current}`
      const ttl = ANIMATION_TTL_MS[spec.type]
      hold(delayMs + ttl)
      const show = () => {
        setAnimations((prev) => [...prev, { ...spec, id } as GameAnimation])
        window.setTimeout(() => {
          setAnimations((prev) => prev.filter((a) => a.id !== id))
        }, ttl)
      }
      if (delayMs > 0) window.setTimeout(show, delayMs)
      else show()
    },
    [hold],
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

  const startSteal = useCallback(
    (event: Extract<GameEvent, { type: 'steal' }>, delayMs = 0) => {
      hold(delayMs + STEAL_MS)
      const show = () => {
        setSteal(() => ({ fromPlayerId: event.fromPlayerId, toPlayerId: event.toPlayerId, card: event.card }))
        window.setTimeout(() => setSteal(null), STEAL_MS)
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

  /** Translates one server event into whatever the table should show for it. */
  const applyEvent = useCallback(
    (event: GameEvent) => {
      const delay = smashDelay.current
      switch (event.type) {
        case 'bust':
          startBust(event)
          pushAnimation({ type: 'screenShake' })
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
          startSteal(event, delay)
          break
        case 'flip7':
          pushAnimation({ type: 'flip7', playerId: event.playerId })
          play('flip7')
          break
        case 'fizzled':
          pushAnimation({ type: 'fizzled', playerId: event.playerId, cardDefId: event.cardDefId })
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
          pushAnimation({ type: 'screenShake' }, delay)
          break
        default:
          break
      }
    },
    [pushAnimation, startBust, startSteal, hold],
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
  const showRoundOutro = !!outroFrom && !!outroUntil && clock >= outroFrom && clock < outroUntil

  const totalMs = (state?.config.turnTimeSeconds ?? 30) * 1000
  const timer: TurnTimer | null = deadline
    ? (() => {
        const remainingMs = Math.max(0, deadline - clock)
        return { remainingMs, totalMs, fraction: Math.min(1, remainingMs / totalMs) }
      })()
    : null

  // ═══════════════════════════════════════════
  // Action card targeting
  // ═══════════════════════════════════════════

  const pendingAction = state?.pendingAction
  const pendingDef: ActionCardInfo | undefined = findAction(catalog, pendingAction?.cardDefId)
  const isPickingTarget = !!pendingAction
  const pendingIsLocal = pendingAction?.playerId === localPlayerId
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

  // Keyed on the physical card, not its type: two strikes in one round share a
  // cardDefId, and keying on that left the second one permanently "already
  // picked" so no seat could be clicked.
  const pendingKey = pendingAction
    ? pendingAction.cardId ?? `${pendingAction.playerId}:${pendingAction.cardDefId}`
    : null
  const [choice, setChoice] = useState<{ key: string; targetId: string } | null>(null)
  const targetChosen = choice && choice.key === pendingKey ? choice.targetId : null

  // The server drops moves made while the table is animating, so a pick sent
  // then would latch `choice` against a card that was never played and leave
  // the picker permanently spent. Refuse it here instead.
  const animating = !!state?.animationGate

  const pickTarget = useCallback(
    (targetId: string) => {
      if (animating) return
      if (!pendingAction || !pendingKey || pendingAction.playerId !== localPlayerId) return
      if (choice?.key === pendingKey) return
      if (!validTargets.includes(targetId)) return
      setChoice({ key: pendingKey, targetId })
      send({ type: 'PLAY_ACTION', targetPlayerId: targetId, cardDefId: pendingAction.cardDefId })
    },
    [animating, pendingAction, pendingKey, localPlayerId, choice, validTargets],
  )

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
    validTargets,
    targetChosen,
    pickTarget,

    hit,
    stay,

    animations,
    dismissAnimation,
    bust,
    steal,
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
