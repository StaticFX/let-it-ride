import { useCallback, useEffect, useRef, useState } from 'react'
import { useGameStore, findAction } from '../state/gameStore'
import { send } from '../net/client'
import type { ActionCardInfo, GameEvent, Player } from '../game/types'

// ─── Animations ───

export type GameAnimation =
  | { type: 'screenShake'; id: string }
  | { type: 'bust'; id: string; playerId: string }
  | { type: 'impact'; id: string; targetId: string }
  | { type: 'flip7'; id: string; playerId: string }
  | { type: 'slots'; id: string }
  | { type: 'timeout'; id: string; playerId: string }

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never

type AnimationSpec = DistributiveOmit<GameAnimation, 'id'>

/** How long each animation stays on screen before it clears itself. */
const ANIMATION_TTL_MS: Record<GameAnimation['type'], number> = {
  screenShake: 600,
  bust: 1600,
  impact: 900,
  flip7: 3200,
  slots: 4800,
  timeout: 1500,
}

export interface TurnTimer {
  remainingMs: number
  totalMs: number
  fraction: number
}

export function useGame() {
  const state = useGameStore((s) => s.state)
  const localPlayerId = useGameStore((s) => s.localPlayerId)
  const events = useGameStore((s) => s.events)
  const eventSeq = useGameStore((s) => s.eventSeq)
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
  // Animation queue — everything is driven by server events
  // ═══════════════════════════════════════════

  const [animations, setAnimations] = useState<GameAnimation[]>([])
  const animationId = useRef(0)
  const lastSeq = useRef(0)

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

  useEffect(() => {
    if (eventSeq === lastSeq.current) return
    lastSeq.current = eventSeq
    for (const event of events) animate(event, pushAnimation)
  }, [eventSeq, events, pushAnimation])

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

  // Remembering *which* pending card the pick was for means the choice clears
  // itself the moment the next card comes up, with no effect to keep in sync.
  const pendingKey = pendingAction ? `${pendingAction.playerId}:${pendingAction.cardDefId}` : null
  const [choice, setChoice] = useState<{ key: string; targetId: string } | null>(null)
  const targetChosen = choice && choice.key === pendingKey ? choice.targetId : null

  const pickTarget = useCallback(
    (targetId: string) => {
      if (!pendingAction || !pendingKey || pendingAction.playerId !== localPlayerId) return
      if (choice?.key === pendingKey) return
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
    targetChosen,
    pickTarget,

    hit,
    stay,

    animations,
    dismissAnimation,
    showRoundIntro,
    dismissRoundIntro,
    timer,
  }
}

export type UseGameReturn = ReturnType<typeof useGame>

/** Translates one server event into whatever the table should show for it. */
function animate(event: GameEvent, push: (spec: AnimationSpec) => void): void {
  switch (event.type) {
    case 'bust':
      push({ type: 'bust', playerId: event.playerId })
      push({ type: 'screenShake' })
      break
    case 'flip7':
      push({ type: 'flip7', playerId: event.playerId })
      break
    case 'slots':
      push({ type: 'slots' })
      break
    case 'actionPlayed':
      push({ type: 'impact', targetId: event.targetPlayerId })
      push({ type: 'screenShake' })
      break
    case 'timeout':
      push({ type: 'timeout', playerId: event.playerId })
      break
    default:
      break
  }
}
