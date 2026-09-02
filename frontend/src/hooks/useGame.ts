import { useCallback, useEffect, useRef, useState } from 'react'
import { useGameStore, findAction } from '../state/gameStore'
import { send } from '../net/client'
import { play } from '../audio/sfx'
import type { ActionCardInfo, Card, GameEvent, Player } from '../game/types'

// ─── Animations ───

export type GameAnimation =
  | { type: 'screenShake'; id: string }
  | { type: 'impact'; id: string; targetId: string }
  | { type: 'freeze'; id: string; playerId: string }
  | { type: 'drawThree'; id: string; playerId: string }
  | { type: 'flip7'; id: string; playerId: string }
  | { type: 'timeout'; id: string; playerId: string }
  | { type: 'fizzled'; id: string; playerId: string; cardDefId: string }
  | { type: 'secondChance'; id: string; playerId: string }

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

type AnimationSpec = DistributiveOmit<GameAnimation, 'id'>

/** How long each animation stays on screen before it clears itself. */
const ANIMATION_TTL_MS: Record<GameAnimation['type'], number> = {
  screenShake: 600,
  impact: 900,
  freeze: 1800,
  drawThree: 1400,
  flip7: 3200,
  timeout: 1500,
  fizzled: 1600,
  secondChance: 1800,
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

/** The slot machine spins until the card it landed on actually arrives. */
export interface SlotsAnimation {
  playerId: string
  card: Card | null
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

  const dismissAnimation = useCallback((type: GameAnimation['type'], id?: string) => {
    setAnimations((prev) => prev.filter((a) => (id ? a.id !== id : a.type !== type)))
  }, [])

  const pushAnimation = useCallback((spec: AnimationSpec) => {
    const id = `anim-${++animationId.current}`
    setAnimations((prev) => [...prev, { ...spec, id } as GameAnimation])
    window.setTimeout(() => {
      setAnimations((prev) => prev.filter((a) => a.id !== id))
    }, ANIMATION_TTL_MS[spec.type])
  }, [])

  const startBust = useCallback((event: Extract<GameEvent, { type: 'bust' }>) => {
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
  }, [])

  const startSteal = useCallback((event: Extract<GameEvent, { type: 'steal' }>) => {
    setSteal(() => ({ fromPlayerId: event.fromPlayerId, toPlayerId: event.toPlayerId, card: event.card }))
    window.setTimeout(() => setSteal(null), 1100)
  }, [])

  const dismissSlots = useCallback(() => setSlots(null), [])

  /** Translates one server event into whatever the table should show for it. */
  const applyEvent = useCallback(
    (event: GameEvent) => {
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
          pushAnimation({ type: 'freeze', playerId: event.playerId })
          play('freeze')
          break
        case 'steal':
          startSteal(event)
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
        case 'slots':
          setSlots(() => ({ playerId: event.playerId, card: null }))
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
          if (event.cardDefId === 'drawThree') {
            pushAnimation({ type: 'drawThree', playerId: event.targetPlayerId })
          } else if (event.cardDefId !== 'slots' && event.cardDefId !== 'freeze') {
            // Freeze and draw 3 have their own visuals; everything else gets
            // the generic slam.
            pushAnimation({ type: 'impact', targetId: event.targetPlayerId })
          }
          pushAnimation({ type: 'screenShake' })
          break
        default:
          break
      }
    },
    [pushAnimation, startBust, startSteal],
  )

  // Subscribe to the socket's event stream rather than reading it back out of
  // render: these arrive from outside React, and reacting to them there is what
  // keeps a burst of events from cascading renders through the whole table.
  useEffect(
    () =>
      useGameStore.subscribe((next, previous) => {
        if (next.eventSeq === previous.eventSeq) return
        for (const event of next.events) applyEvent(event)
      }),
    [applyEvent],
  )

  // ═══════════════════════════════════════════
  // Round intro
  // ═══════════════════════════════════════════

  const [introDismissedFor, setIntroDismissedFor] = useState(0)
  const showRoundIntro = phase === 'PLAYING' && round >= 1 && introDismissedFor !== round
  const dismissRoundIntro = useCallback(() => setIntroDismissedFor(round), [round])

  // ═══════════════════════════════════════════
  // Turn clock
  // ═══════════════════════════════════════════

  const deadline = state?.turnDeadline
  const [clock, setClock] = useState(() => Date.now())

  useEffect(() => {
    if (!deadline) return
    const interval = window.setInterval(() => setClock(Date.now()), 100)
    return () => window.clearInterval(interval)
  }, [deadline])

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
  // The server works out who a card may legally hit; the picker offers no others.
  const validTargets = pendingAction?.validTargets ?? []

  const pendingKey = pendingAction ? `${pendingAction.playerId}:${pendingAction.cardDefId}` : null
  const [choice, setChoice] = useState<{ key: string; targetId: string } | null>(null)
  const targetChosen = choice && choice.key === pendingKey ? choice.targetId : null

  const pickTarget = useCallback(
    (targetId: string) => {
      if (!pendingAction || !pendingKey || pendingAction.playerId !== localPlayerId) return
      if (choice?.key === pendingKey) return
      if (!pendingAction.validTargets.includes(targetId)) return
      setChoice({ key: pendingKey, targetId })
      send({ type: 'PLAY_ACTION', targetPlayerId: targetId, cardDefId: pendingAction.cardDefId })
    },
    [pendingAction, pendingKey, localPlayerId, choice],
  )

  // ═══════════════════════════════════════════
  // Player actions
  // ═══════════════════════════════════════════

  const isDealing = (state?.dealQueue.length ?? 0) > 0
  const isInterrupted = isPickingTarget || !!state?.forcedDraws || isDealing
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
    dismissRoundIntro,
    timer,
  }
}

export type UseGameReturn = ReturnType<typeof useGame>
