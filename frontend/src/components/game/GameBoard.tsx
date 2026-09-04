import { useEffect, useState } from 'react'
import type { Card as CardType, Player } from '../../game/types'
import { useGame } from '../../hooks/useGame'
import { useWindowSize } from '../../hooks/useWindowSize'
import { findAction, useCatalog } from '../../state/gameStore'
import { RoughBox } from '../ui/RoughShapes'
import { PlayingCard } from '../cards/PlayingCard'
import { CardBack } from '../cards/CardBack'
import { DealtCard } from '../cards/DealtCard'
import { retainDealtCards } from '../cards/dealtCards'
import { MarkRow } from './MarkSlip'
import { PlayerAvatar } from './PlayerAvatar'
import { Scoreboard } from './Scoreboard'
import { SpunHand } from './SpunHand'
import { TableNote } from './TableNote'
import { TableShader } from './TableShader'
import { TurnClock } from './TurnClock'
import { SketchButton } from '../ui/Button'
import { SoundToggle } from '../ui/SoundToggle'
import { RoundIntro } from '../overlays/RoundIntro'
import { RoundOutro } from '../overlays/RoundOutro'
import { ImpactParticles } from '../overlays/ImpactParticles'
import { Lucky7Overlay } from '../overlays/Lucky7Overlay'
import { SlotMachine } from '../overlays/SlotMachine'
import { FreezeBurst } from '../overlays/FreezeBurst'
import { DrawThreeStamp } from '../overlays/DrawThreeStamp'
import { StolenCard } from '../overlays/StolenCard'
import { ChoicePicker } from '../overlays/ChoicePicker'
import { CoinToss } from '../overlays/CoinToss'
import { SpinningBottle } from '../overlays/SpinningBottle'
import { TableSwirl } from '../overlays/TableSwirl'
import { Showdown } from '../overlays/Showdown'
import { Shop } from '../overlays/Shop'

const SEAT_POSITIONS = [
  { left: '9%', top: '46%' },
  { left: '27%', top: '13%' },
  { left: '73%', top: '13%' },
  { left: '91%', top: '46%' },
]

/**
 * How much clock is left before the countdown stops being a detail in the
 * corner and moves to the middle of the table, where everyone is already
 * looking.
 *
 * Whichever comes first, so it reads as the closing stretch of the turn rather
 * than a fixed count: on the shortest clock the lobby offers — ten seconds —
 * a flat ten would put it at the deck for the whole turn and the corner would
 * never be used at all.
 */
/**
 * What the table says when it stops for a question nothing was drawn for. A
 * card explains itself by arriving; these have to say what they are.
 */
const DEFERRED_PROMPTS: Record<string, string> = {
  bust: 'who comes with you?',
  flipChoice: 'the bonus is yours — or theirs',
  flipTarget: 'take it off who?',
  throw: 'throw!',
  bet: 'bet a card, face down',
  buy: 'buy something',
}

const CLOCK_CLOSE_MS = 10_000
const CLOCK_CLOSE_FRACTION = 0.4

export function GameBoard() {
  const game = useGame()
  const catalog = useCatalog()
  const { w, h } = useWindowSize()
  const [hoveredPlayerId, setHoveredPlayerId] = useState<string | null>(null)
  const [inspectedCard, setInspectedCard] = useState<CardType | null>(null)
  // Whether the felt is being drawn by the shader. If it is not — no WebGL2 —
  // the CSS vignette below goes back to carrying the turn on its own.
  const [feltLive, setFeltLive] = useState(false)

  const {
    players, me, others, currentPlayer, turnIndex, round, roundStartPlayer,
    deckCount, discardCount, localPlayerId, isMyTurn, isEliminated, mustDraw,
    isDealing, dealingPlayerId, pendingDef, isPickingTarget, pendingIsLocal, validTargets,
    targetChosen, pickTarget, hit, stay, animating,
    pendingOptions, needsChoice, seatIsImplied, optionChosen, pickOption,
    picksCards, picksNeeded, cardsChosen, canPickCard, pickCard, pendingIsDeferred, pendingPhase, validCards,
    picksFromCatalog, offers, purse, pickOffer,
    pendingAwaitingOthers, responders, answeredBy,
    animations, bust, flights, slots, dismissSlots,
    showRoundIntro, showRoundOutro, introUntil, outroUntil, timer,
  } = game

  // What is face-up on the table right now. Everything else is forgotten, so a
  // card that goes back to the discard pile deals in again the next time the
  // deck picks it up — see [retainDealtCards].
  const cardsOnTable = players.flatMap((p) => [...p.hand, ...p.passives]).map((c) => c.id).join(' ')
  useEffect(() => {
    retainDealtCards(cardsOnTable.split(' ').filter(Boolean))
  }, [cardsOnTable])

  const deckCenter = { x: w / 2 - 20, y: h * 0.42 }
  /** Where a card being played is held up before it is sent at a seat. */
  const cardStage = { x: w / 2, y: h * 0.62 }

  /**
   * Where a seat sits on screen. [others] is already in play order starting
   * from whoever follows me, and SEAT_POSITIONS runs clockwise from the seat
   * next to mine — so reading one against the other is what makes the table go
   * round the way the turn does. Anything that flies between seats measures
   * from here, so this is the only place the mapping may live.
   */
  function seatOfId(playerId: string) {
    if (playerId === me?.id) return { x: w / 2, y: h - 120 }
    const seat = others.findIndex((p) => p.id === playerId)
    const pos = SEAT_POSITIONS[Math.min(Math.max(seat, 0), SEAT_POSITIONS.length - 1)]
    return { x: (parseFloat(pos.left) / 100) * w, y: (parseFloat(pos.top) / 100) * h }
  }

  /** Where a bottle lands, and what every bearing is measured from. */
  const tableCenter = { x: w / 2, y: h * 0.46 }

  /**
   * Degrees clockwise from north to [playerId]'s seat, as seen from the middle
   * of the table. Every client works this out from its own seat layout, which
   * is why the bottle's landing angle cannot be a constant.
   */
  function bearingTo(playerId: string) {
    const seat = seatOfId(playerId)
    return (Math.atan2(seat.y - tableCenter.y, seat.x - tableCenter.x) * 180) / Math.PI + 90
  }

  // ─── Animation lookups ───
  // A bust outranks a slam when both land in the same beat: the round ending
  // under somebody is the bigger of the two things that just happened.
  const shakes = animations.filter((a) => a.type === 'screenShake')
  const shakeClass = shakes.length === 0
    ? ''
    : shakes.some((s) => s.strength === 'bust') ? 'shake-bust' : 'shake'
  const impact = animations.find((a) => a.type === 'impact')
  const flip7 = animations.find((a) => a.type === 'flip7')
  const smashes = animations.filter((a) => a.type === 'smash')
  const freezes = animations.filter((a) => a.type === 'freeze')
  const drawThrees = animations.filter((a) => a.type === 'drawThree')
  const fizzles = animations.filter((a) => a.type === 'fizzled')
  const secondChances = animations.filter((a) => a.type === 'secondChance')
  const timedOutIds = animations.filter((a) => a.type === 'timeout').map((a) => a.playerId)
  const coinTosses = animations.filter((a) => a.type === 'coinFlip')
  const bottleSpins = animations.filter((a) => a.type === 'bottleSpin')
  const tableSpin = animations.find((a) => a.type === 'tableSpun')
  const showdown = animations.find((a) => a.type === 'showdown')

  /**
   * How far [playerId]'s hand has to be thrown back for the spin, or null when
   * this seat is not part of it. The event lists the seats in table order, and
   * a hand that went "right" came from the id before it in that list.
   */
  function handSlide(playerId: string) {
    if (!tableSpin) return null
    const ids = tableSpin.playerIds
    const index = ids.indexOf(playerId)
    if (index < 0 || ids.length < 2) return null
    const fromId = tableSpin.direction === 'left'
      ? ids[(index + 1) % ids.length]
      : ids[(index - 1 + ids.length) % ids.length]
    const from = seatOfId(fromId)
    const own = seatOfId(playerId)
    return { dx: from.x - own.x, dy: from.y - own.y }
  }

  const showButtons = isMyTurn && !isEliminated && !isPickingTarget
  const canInspect = !isPickingTarget
  // Somebody is on the clock, so every other seat can step back — a lit seat
  // only reads as lit if the ones around it are not.
  const someoneOnClock = !isDealing && !isPickingTarget && currentPlayer?.status === 'active'
  const myHovered = hoveredPlayerId === me?.id
  const mySpread = isMyTurn || myHovered
  // Once the clock is nearly out it stops being a corner detail and goes up
  // over the deck instead — one clock, in the place worth looking at.
  const clockIsClose =
    !!timer &&
    !isDealing &&
    timer.remainingMs <= Math.min(CLOCK_CLOSE_MS, timer.totalMs * CLOCK_CLOSE_FRACTION)

  // How hard the felt should be breathing. Only your own clock counts — the
  // table stays still while somebody else is thinking.
  const feltUrgency =
    isMyTurn && clockIsClose && timer
      ? 1 - Math.min(1, timer.remainingMs / Math.min(CLOCK_CLOSE_MS, timer.totalMs * CLOCK_CLOSE_FRACTION))
      : 0


  /** How a card in `playerId`'s hand should be treated by the bust animation. */
  function bustRole(playerId: string, cardId: string): 'none' | 'match' | 'other' {
    if (bust?.playerId !== playerId || bust.phase !== 'reveal') return 'none'
    if (!bust.cardId && !bust.matchedId) return 'none'
    return cardId === bust.cardId || cardId === bust.matchedId ? 'match' : 'other'
  }

  const scattering = (playerId: string) => bust?.playerId === playerId && bust.phase === 'scatter'
  const isFrozen = (playerId: string) => freezes.some((f) => f.playerId === playerId)

  function statusBadge(player: Player) {
    if (player.status === 'bust') return <span className="status-badge text-[var(--accent)] border border-[var(--accent)]">bust!</span>
    if (player.status === 'stayed') return <span className="status-badge border border-[var(--ink)]">out</span>
    if (!player.connected) return <span className="status-badge border border-[var(--ink-soft)] text-[var(--ink-soft)]">away</span>
    return null
  }

  /**
   * How a card behaves while a card-picking prompt is on the table: the ones it
   * may be pointed at light up and take the click, the ones it may not step
   * back, and inspecting is out of the way until the pick is made.
   *
   * Null when nothing is being picked, which is the ordinary case — the card
   * then behaves exactly as it always has.
   */
  /**
   * Whether this hand is currently being picked from, and so has to be laid out
   * flat instead of fanned. In a fan every card but the last is half covered by
   * the next one along, which is fine to read and hopeless to aim at — and a
   * prompt that asks for a card has to make every card its own target.
   */
  function isLaidOut(player: Player) {
    return picksCards && [...player.hand, ...player.passives].some((c) => validCards.includes(c.id))
  }

  function pickState(card: CardType) {
    if (!picksCards) return null
    const picked = cardsChosen.includes(card.id)
    const canPick = canPickCard(card.id)
    return {
      picked,
      canPick,
      className: picked ? 'card-picked' : canPick ? 'card-pickable' : 'card-unpickable',
      onClick: canPick
        ? (e: React.MouseEvent) => {
            e.stopPropagation()
            pickCard(card.id)
          }
        : undefined,
    }
  }

  function handCard(player: Player, card: CardType, idx: number, size: 'small' | 'normal', spread: boolean) {
    const role = bustRole(player.id, card.id)
    const pick = pickState(card)
    const laidOut = isLaidOut(player)
    const scatter = scattering(player.id)
    const fanAngle = laidOut ? 0 : (idx - (player.hand.length - 1) / 2) * (spread ? (size === 'normal' ? 5 : 6) : 4)

    return (
      <div
        key={card.id}
        onClick={pick
          ? pick.onClick
          : canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
        data-testid="hand-card"
        data-card-id={card.id}
        data-card-kind={card.kind}
        data-card-label={card.label}
        data-pickable={pick?.canPick}
        data-picked={pick?.picked}
        className={`origin-bottom cursor-pointer ${pick?.className ?? ''}`}
        style={{
          marginLeft: idx === 0 ? 0 : laidOut ? 3 : size === 'normal' ? (spread ? -32 : -38) : spread ? -18 : -30,
          transform: `rotate(${fanAngle}deg) translateY(${Math.abs(fanAngle) * 0.7}px)`,
          transition: 'margin-left 280ms ease, transform 280ms cubic-bezier(.2,.9,.3,1.3)',
        }}
      >
        <div
          className={role === 'match' ? 'relative bust-match' : role === 'other' ? 'bust-dim' : undefined}
          style={scatter
            ? { '--bust-spin': `${(idx % 2 === 0 ? -1 : 1) * (15 + idx * 5)}deg`, animation: `bustFlyUp 900ms ${idx * 60}ms cubic-bezier(.2,0,.6,1) forwards` } as React.CSSProperties
            : undefined}
        >
          <DealtCard card={card} from={deckCenter}>
            <PlayingCard card={card} size={size} />
          </DealtCard>
          {/* Name the clash on the newer of the two cards only. */}
          {role === 'match' && card.id === bust?.cardId && (
            <div className="bust-match-tag">same {card.label}!</div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div
      className={`game-shell ${shakeClass}`}
      data-testid="game-board"
      data-round={round}
      data-my-turn={isMyTurn}
      data-dealing={isDealing}
      data-picking-target={isPickingTarget}
      data-my-status={me?.status ?? 'none'}
    >
      {showRoundIntro && introUntil && (
        <RoundIntro
          round={round}
          startingPlayerName={players[roundStartPlayer]?.name ?? '...'}
          untilMs={introUntil}
        />
      )}

      {showRoundOutro && outroUntil && (
        <RoundOutro
          round={round}
          winner={players.find((p) => p.id === game.state?.roundWinnerId) ?? null}
          points={game.state?.roundDeltas[game.state?.roundWinnerId ?? ''] ?? 0}
          flip7={!!game.state?.flip7PlayerId}
          flipTarget={game.state?.flip7Target ?? catalog?.flip7Target ?? 7}
          untilMs={outroUntil}
        />
      )}

      {/* The table itself. Bottom of the stack and pointer-transparent, so the
          shader can do what it likes without ever touching the UI above it.
          A bust shakes the whole shell rather than rippling the felt; the
          shader's ripple channel is there for whatever wants it next. */}
      <TableShader myTurn={isMyTurn} urgency={feltUrgency} onReady={setFeltLive} />

      {/* It is your move — say so with the whole screen, not just the corner.
          The shader draws this when it is up; this is the fallback. */}
      {!feltLive && (
        <div
          className={`turn-vignette ${isMyTurn ? 'is-active' : ''} ${clockIsClose ? 'is-urgent' : ''}`}
          data-testid="turn-vignette"
          data-active={isMyTurn}
        >
          <div className="turn-vignette-glow" />
        </div>
      )}

      {/* Outer frame */}
      <div className="absolute inset-0 pointer-events-none z-[1]">
        <div className="absolute inset-3.5">
          <RoughBox width={w - 28} height={h - 28} stroke="var(--ink)" strokeWidth={2} roughness={2.0} boil={false} />
        </div>
        <div className="absolute inset-[22px] opacity-55">
          <RoughBox width={w - 44} height={h - 44} stroke="var(--ink)" strokeWidth={1} roughness={2.4} boil={false} />
        </div>
      </div>

      {/* Top-left */}
      <div className="absolute top-7 left-[38px] z-[55] flex items-center gap-3">
        <h1 className="text-[38px] -rotate-[1.5deg] sway-slow">let it ride</h1>
        <SoundToggle className="mt-1.5" />
      </div>

      {/* Top-right */}
      <div className="absolute top-7 right-[38px] z-[55] text-right flex flex-col items-end gap-1">
        <small className="rotate-1 block" data-testid="round-label">round {String(round).padStart(2, '0')}</small>
        <div className="display text-[30px] leading-none flex items-center gap-2.5 justify-end rotate-1 sway-mid" data-testid="turn-name">
          <span className="text-[var(--accent)]">→</span>
          {isPickingTarget
            ? responders.length > 1
              ? 'everyone'
              : players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? '...'
            : isDealing ? 'dealing…' : currentPlayer?.name || '...'}
        </div>
        <small className="rotate-1 block" data-testid="turn-prompt">
          {isPickingTarget
            ? pendingIsLocal
              ? pendingIsDeferred
                ? DEFERRED_PROMPTS[pendingPhase] ?? 'your call!'
                : picksCards
                  ? `pick ${picksNeeded - cardsChosen.length} more card${picksNeeded - cardsChosen.length === 1 ? '' : 's'}!`
                  : needsChoice ? 'your call!' : 'pick a target!'
              // Answered, and the rest of the table has not. Nobody is told
              // what anybody said until every one of them is in.
              : pendingAwaitingOthers
                ? `waiting on ${responders.length - answeredBy.length} more…`
                : picksCards ? 'is picking cards…' : needsChoice ? 'is choosing…' : 'is picking a target…'
            : isDealing ? '' : isMyTurn ? 'your move!' : 'to act…'}
        </small>
        {timer && !clockIsClose && <TurnClock timer={timer} />}
      </div>

      {/* The last ten seconds, up where the deck is */}
      {timer && clockIsClose && (
        <div
          className="absolute left-1/2 z-[60] flex -translate-x-1/2 -translate-y-full flex-col items-center gap-1 pointer-events-none"
          style={{ top: h * 0.42 - 96 }}
          data-testid="deck-clock"
        >
          <TurnClock timer={timer} size="lg" />
          <small className={`display whitespace-nowrap ${timer.remainingMs <= 5000 ? 'text-[var(--accent)]' : ''}`}>
            {isMyTurn ? 'your move — quick!' : `${currentPlayer?.name ?? 'someone'} is running out`}
          </small>
        </div>
      )}

      {/* Center piles */}
      <div className="absolute left-1/2 top-[42%] -translate-x-1/2 -translate-y-1/2 flex items-center gap-[38px] z-[4]">
        <div
          className={`relative ${isMyTurn ? 'cursor-pointer' : 'cursor-default'}`}
          onClick={isMyTurn ? hit : undefined}
          data-testid="draw-pile"
          data-count={deckCount}
        >
          {[0, 1, 2, 3].map((i) => (
            <div
              key={i}
              style={{
                position: i === 3 ? 'relative' : 'absolute',
                top: i === 3 ? 0 : -i * 2,
                left: i === 3 ? 0 : i * 2,
                transform: i === 3 ? 'rotate(0deg)' : `rotate(${(i * 2 - 3) * 1.2}deg)`,
              }}
            >
              <CardBack size="deck" />
            </div>
          ))}
          <div className="absolute -bottom-7 left-1/2 -translate-x-1/2 -rotate-1 display text-base whitespace-nowrap">
            {String(deckCount).padStart(2, '0')} · draw
          </div>
        </div>
        <div className="relative w-[108px] h-[152px] flex items-center justify-center" data-testid="discard-pile" data-count={discardCount}>
          <RoughBox width={108} height={152} stroke="var(--ink)" strokeWidth={1.8} roughness={2.0} dashed boil={false} />
          <div className="display text-[17px] text-[var(--ink-soft)] text-center leading-tight -rotate-3">
            discard<br />{discardCount}
          </div>
        </div>
      </div>

      {/* Other players */}
      {others.map((p, i) => {
        const seatPos = SEAT_POSITIONS[Math.min(i, SEAT_POSITIONS.length - 1)]
        const pIndex = players.findIndex((pl) => pl.id === p.id)
        const isActive = !isDealing && !isPickingTarget && pIndex === turnIndex && p.status === 'active'
        const isBeingDealt = dealingPlayerId === p.id
        const dimmed = (p.status === 'stayed' || p.status === 'bust') && !isBeingDealt
        const backgrounded = someoneOnClock && !isActive && p.status === 'active'
        // A card that asks a question resolves on its own drawer, so there is
        // nothing to point at and the seats stay out of it — see [seatIsImplied].
        const targetable = pendingIsLocal && !seatIsImplied && !targetChosen && validTargets.includes(p.id)
        const targetHovered = targetable && hoveredPlayerId === p.id
        const spread = hoveredPlayerId === p.id
        const slide = handSlide(p.id)

        return (
          <div
            key={p.id}
            onClick={targetable ? () => pickTarget(p.id) : undefined}
            onMouseEnter={() => setHoveredPlayerId(p.id)}
            onMouseLeave={() => setHoveredPlayerId((id) => (id === p.id ? null : id))}
            data-testid="seat"
            data-player-id={p.id}
            data-player-name={p.name}
            data-status={p.status}
            data-hand-value={p.handValue}
            data-hand-size={p.hand.length}
            data-passive-count={p.passives.length}
            data-targetable={targetable}
            data-active={isActive}
            data-bot={p.isBot}
            style={{
              position: 'absolute',
              ...seatPos,
              transform: `translate(-50%, -50%) scale(${isBeingDealt ? 1.12 : targetHovered ? 1.15 : isActive ? 1.09 : 1})`,
              // A targetable seat has to sit above the local player's bar
              // (z-8), or on a short window the bar swallows the click.
              zIndex: targetable ? 30 : isActive || isBeingDealt ? 10 : 3,
              opacity: dimmed ? 0.45 : isPickingTarget && !targetable ? 0.3 : backgrounded ? 0.7 : 1,
              transition: 'opacity 280ms, transform 350ms cubic-bezier(.2,.9,.3,1.3)',
              cursor: targetable ? 'crosshair' : isPickingTarget ? 'not-allowed' : 'default',
            }}
          >
            <div
              className={`flex flex-col items-center gap-2 ${isFrozen(p.id) ? 'frozen-seat' : ''}`}
              style={{ animation: impact?.targetId === p.id ? 'impactShake 500ms ease-out' : 'none' }}
            >
              <div className={`hover-glow ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
              {isActive && <div className="turn-halo" />}
              <div className="flex items-center gap-2.5 relative z-[1]">
                <PlayerAvatar
                  initial={p.name.charAt(0)}
                  active={isActive || isBeingDealt || targetHovered}
                  onTurn={isActive}
                  id={i + 1}
                />
                <div className="text-left">
                  <div className="display text-[22px] leading-none relative">
                    {p.name}
                    {p.isBot && <small className="ml-1 font-normal">bot</small>}
                    {bust?.playerId === p.id && <div className="bust-strike" />}
                  </div>
                  <div className="flex items-center gap-2 mt-0.5">
                    <span className={`number text-[22px] leading-none ${p.status === 'bust' ? 'text-[var(--accent)]' : ''}`}>
                      {p.handValue}
                    </span>
                    {statusBadge(p)}
                    {timedOutIds.includes(p.id) && <span className="status-badge border border-[var(--ink-soft)]">timed out</span>}
                    {isPickingTarget && pendingIsLocal && !seatIsImplied && !targetable && p.status === 'active' && (
                      <span className="status-badge border border-[var(--ink-soft)] text-[var(--ink-soft)]">
                        {p.hand.length === 0 ? 'no cards' : 'no target'}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              <SpunHand spinId={tableSpin?.id ?? null} dx={slide?.dx ?? 0} dy={slide?.dy ?? 0}>
                <div
                  className="flex min-h-[86px] px-1.5 py-0.5 cursor-pointer origin-bottom transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                  style={{ transform: spread ? 'scale(1.15)' : 'scale(1)' }}
                >
                  {p.hand.map((card, idx) => handCard(p, card, idx, 'small', spread))}
                  {p.passives.length > 0 && (
                    <>
                      <div className="w-2.5 shrink-0" />
                      {p.passives.map((card, idx) => {
                        const pick = pickState(card)
                        return (
                        <div
                          key={card.id}
                          onClick={pick
                            ? pick.onClick
                            : canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
                          data-testid="passive-card"
                          data-card-id={card.id}
                          data-card-def-id={card.defId}
                          data-pickable={pick?.canPick}
                          data-picked={pick?.picked}
                          className={`card-fan-transition opacity-85 cursor-pointer ${pick?.className ?? ''}`}
                          style={{
                            marginLeft: idx === 0 ? 0 : isLaidOut(p) ? 3 : -30,
                            transform: isLaidOut(p)
                              ? 'none'
                              : `rotate(${(idx - (p.passives.length - 1) / 2) * 3}deg)`,
                          }}
                        >
                          <DealtCard card={card} from={deckCenter}>
                            <PlayingCard card={card} size="small" dimmed={dimmed} />
                          </DealtCard>
                        </div>
                        )
                      })}
                    </>
                  )}
                </div>
              </SpunHand>

              <MarkRow marks={p.marks} dimmed={dimmed} />
            </div>
          </div>
        )
      })}

      {/* My hand */}
      {me && (() => {
        const targetable = pendingIsLocal && !seatIsImplied && !targetChosen && validTargets.includes(me.id)
        const targetHovered = targetable && hoveredPlayerId === me.id
        const dimmed = !isMyTurn && !isEliminated && !isDealing
        const slide = handSlide(me.id)
        // Your own seat gets the same halo and ring as everyone else's. The
        // vignette already says the move is yours; this says which of the seats
        // on screen that means, in the shape you have been reading all round.
        const myTurnNow = isMyTurn && !isEliminated

        return (
          <div
            className="absolute bottom-0 left-0 right-0 flex flex-col items-center z-[8]"
            style={{
              paddingBottom: showButtons ? 90 : 14,
              transition: 'padding-bottom 520ms cubic-bezier(.2,.9,.25,1.25)',
            }}
          >
            <div style={{ animation: impact?.targetId === me.id ? 'impactShake 500ms ease-out' : 'none' }}>
              <div
                onClick={targetable ? () => pickTarget(me.id) : undefined}
                onMouseEnter={() => setHoveredPlayerId(me.id)}
                onMouseLeave={() => setHoveredPlayerId((id) => (id === me.id ? null : id))}
                data-testid="seat"
                data-self="true"
                data-player-id={me.id}
                data-player-name={me.name}
                data-status={me.status}
                data-hand-value={me.handValue}
                data-hand-size={me.hand.length}
                data-passive-count={me.passives.length}
                data-targetable={targetable}
                className={`flex items-end gap-[18px] px-4 py-2 relative transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)] ${isFrozen(me.id) ? 'frozen-seat' : ''}`}
                style={{
                  cursor: targetable ? 'crosshair' : 'default',
                  transform: targetHovered ? 'scale(1.05)' : 'scale(1)',
                }}
              >
                <div className={`hover-glow-self ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
                <div className="flex flex-col items-end gap-1.5 min-w-[120px] relative">
                  {myTurnNow && <div className="turn-halo" />}
                  <div
                    className="flex items-center gap-2.5 relative z-[1] transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                    style={{ transform: `scale(${dealingPlayerId === me.id ? 1.1 : myTurnNow ? 1.06 : 1})` }}
                  >
                    <PlayerAvatar
                      initial={me.name.charAt(0)}
                      active={myTurnNow || dealingPlayerId === me.id}
                      onTurn={myTurnNow}
                      id={0}
                    />
                    <div>
                      <div className="display text-[26px] leading-none relative">
                        {me.name}
                        {bust?.playerId === me.id && <div className="bust-strike" />}
                      </div>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <span className={`number text-[28px] leading-none ${me.status === 'bust' ? 'text-[var(--accent)]' : ''}`}>
                          {me.handValue}
                        </span>
                        {statusBadge(me)}
                      </div>
                    </div>
                  </div>
                  <MarkRow marks={me.marks} />
                </div>

                <SpunHand spinId={tableSpin?.id ?? null} dx={slide?.dx ?? 0} dy={slide?.dy ?? 0}>
                  <div
                    className="flex items-end px-3 origin-bottom"
                    style={{
                      minHeight: mySpread ? 180 : 110,
                      transform: mySpread ? 'scale(1)' : dealingPlayerId === me.id ? 'scale(0.9)' : 'scale(0.75)',
                      opacity: bust?.playerId === me.id ? 1 : isEliminated ? 0.4 : dimmed && !myHovered ? 0.55 : 1,
                      transition:
                        'transform 420ms cubic-bezier(.2,.9,.3,1.3), opacity 320ms ease, min-height 420ms cubic-bezier(.2,.9,.3,1.3)',
                      filter: dimmed && !isEliminated && !myHovered ? 'grayscale(0.6)' : 'none',
                    }}
                  >
                    {me.hand.map((card, idx) => handCard(me, card, idx, mySpread ? 'normal' : 'small', mySpread))}
                    {me.passives.length > 0 && (
                      <>
                        <div className="shrink-0" style={{ width: mySpread ? 18 : 12 }} />
                        {me.passives.map((card, idx) => {
                          const pick = pickState(card)
                          return (
                          <div
                            key={card.id}
                            onClick={pick
                              ? pick.onClick
                              : canInspect ? () => setInspectedCard(card) : undefined}
                            data-testid="passive-card"
                            data-card-id={card.id}
                            data-card-def-id={card.defId}
                            data-pickable={pick?.canPick}
                            data-picked={pick?.picked}
                            className={`card-fan-transition-slow opacity-85 cursor-pointer ${pick?.className ?? ''}`}
                            style={{
                              marginLeft: idx === 0 ? 0 : isLaidOut(me) ? 3 : -38,
                              transform: isLaidOut(me)
                                ? 'none'
                                : `rotate(${(idx - (me.passives.length - 1) / 2) * 3}deg)`,
                            }}
                          >
                            <DealtCard card={card} from={deckCenter}>
                              <PlayingCard card={card} size="small" />
                            </DealtCard>
                          </div>
                          )
                        })}
                      </>
                    )}
                  </div>
                </SpunHand>
              </div>
            </div>
          </div>
        )
      })()}

      {/* Scoreboard */}
      <div className="absolute left-[38px] bottom-9 z-[90]">
        <Scoreboard players={players} currentPlayerId={currentPlayer?.id || ''} localPlayerId={localPlayerId} />
      </div>

      {/* What is being played, opposite the running score */}
      {game.state && (
        <div className="absolute right-[38px] bottom-9 z-[55]">
          <TableNote config={game.state.config} />
        </div>
      )}

      {/* The card waiting for a target. Once a seat is picked it only winds up —
          the trip across the table is the smash below, which the server starts
          and which every client sees, not just the one that did the picking. */}
      {isPickingTarget && pendingDef && (() => {
        // A pick is committed when the answer is complete and on its way — not
        // when the first click of it lands. A card that wants two cards is only
        // half answered after one, and the picker has to stay live.
        const committed = picksCards ? cardsChosen.length >= picksNeeded : !!targetChosen

        return (
          <div
            className="fixed z-[200] pointer-events-none flex flex-col items-center gap-2.5"
            data-testid="pending-action"
            data-card-def-id={pendingDef.id}
            data-card-id={game.pendingAction?.cardId ?? ''}
            data-mine={pendingIsLocal}
            data-chosen={committed ? targetChosen ?? '' : ''}
            data-picked-cards={cardsChosen.join(' ')}
            data-needs-choice={needsChoice}
            data-option={optionChosen ?? ''}
            style={{
              left: cardStage.x,
              top: cardStage.y,
              transform: `translate(-50%, -50%) rotate(${committed ? -7 : 0}deg) scale(${committed ? 2 : 1.8})`,
              transition: 'transform 260ms cubic-bezier(.3,.8,.4,1.3)',
            }}
          >
            <div style={{ animation: committed ? 'none' : 'swayMore 1.8s ease-in-out infinite' }}>
              <PlayingCard
                card={{ id: 'pending', kind: 'action', label: pendingDef.name, value: 0, defId: pendingDef.id }}
                size="deck"
              />
            </div>
            {pendingIsLocal && !seatIsImplied && !targetChosen && (
              <div className="pick-target-label">pick a target!</div>
            )}
            {/* Everyone else is watching the same card wait on one player, so
                the table says which of the two things it is waiting for. */}
            {!pendingIsLocal && (
              <div className="pick-target-label">
                {players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? 'someone'}
                {needsChoice ? ' is choosing…' : ' is picking…'}
              </div>
            )}
          </div>
        )
      })()}

      {/* …and the question it asks, when it asks one.
          Not gated on the animation the way the hit/stay buttons are: the card
          and the animation of it being drawn arrive together, so a picker that
          hid itself while the table was animating would be invisible for
          exactly the beat the player is being asked to answer in. The answer is
          held back rather than the question — see the send in useGame. */}
      {isPickingTarget && pendingIsLocal && picksFromCatalog && (
        <Shop
          offers={offers}
          purse={purse}
          chosen={cardsChosen[0] ?? null}
          waiting={animating}
          onPick={pickOffer}
          x={cardStage.x}
          y={Math.min(cardStage.y - 40, h - 320)}
        />
      )}

      {isPickingTarget && pendingIsLocal && needsChoice && (
        <ChoicePicker
          cardDefId={game.pendingAction?.cardDefId ?? ''}
          options={pendingOptions}
          chosen={optionChosen}
          waiting={animating}
          onPick={pickOption}
          x={cardStage.x}
          y={Math.min(cardStage.y + 142, h - 168)}
        />
      )}

      {/* …and the card coming down on whoever it was pointed at */}
      {smashes.map((s) => {
        const def = findAction(catalog, s.cardDefId)
        if (!def) return null
        const seat = seatOfId(s.targetId)

        return (
          <div
            key={s.id}
            className="fixed z-[210] pointer-events-none smash-card"
            data-testid="smash-card"
            data-card-def-id={s.cardDefId}
            data-target-id={s.targetId}
            style={{
              left: seat.x,
              top: seat.y - 40,
              '--smash-dx': `${cardStage.x - seat.x}px`,
              '--smash-dy': `${cardStage.y - (seat.y - 40)}px`,
            } as React.CSSProperties}
          >
            <PlayingCard
              card={{ id: `smash-${s.id}`, kind: 'action', label: def.name, value: 0, defId: def.id }}
              size="deck"
            />
          </div>
        )
      })}

      {/* Per-card animations */}
      {impact && (() => {
        const pos = seatOfId(impact.targetId)
        return <ImpactParticles x={pos.x} y={pos.y - 60} />
      })()}

      {freezes.map((f) => {
        const pos = seatOfId(f.playerId)
        return <FreezeBurst key={f.id} x={pos.x} y={pos.y} />
      })}

      {drawThrees.map((d) => {
        const pos = seatOfId(d.playerId)
        return <DrawThreeStamp key={d.id} x={pos.x} y={pos.y - 20} />
      })}

      {showdown && (
        <Showdown title={showdown.title} sides={showdown.sides} footnote={showdown.footnote} />
      )}

      {flights.map((flight) => (
        <StolenCard
          key={flight.id}
          card={flight.card}
          from={seatOfId(flight.fromPlayerId)}
          to={seatOfId(flight.toPlayerId)}
        />
      ))}

      {secondChances.map((s) => {
        const pos = seatOfId(s.playerId)
        return (
          <div
            key={s.id}
            className="fixed z-[215] pointer-events-none display text-[26px] font-bold text-[var(--passive)] second-chance-pop whitespace-nowrap"
            style={{ left: pos.x, top: pos.y - 70 }}
          >
            ♡ second life!
          </div>
        )
      })}

      {fizzles.map((f) => (
        <div
          key={f.id}
          className="fixed z-[215] pointer-events-none text-center fizzle-note"
          style={{ left: w / 2, top: h * 0.52 }}
        >
          <div className="display text-[22px] font-bold text-[var(--ink-soft)] whitespace-nowrap">
            {findAction(catalog, f.cardDefId)?.name ?? 'that card'} had nobody to hit
          </div>
          <small>drawing a replacement…</small>
        </div>
      ))}

      {coinTosses.map((c) => (
        <CoinToss key={c.id} call={c.call} result={c.result} x={tableCenter.x} y={tableCenter.y} />
      ))}

      {bottleSpins.map((b) => (
        <SpinningBottle
          key={b.id}
          bearing={bearingTo(b.victimId)}
          victimName={players.find((p) => p.id === b.victimId)?.name ?? 'someone'}
          x={tableCenter.x}
          y={tableCenter.y}
        />
      ))}

      {tableSpin && <TableSwirl direction={tableSpin.direction} x={tableCenter.x} y={tableCenter.y} />}

      {slots && <SlotMachine card={slots.card} onDone={dismissSlots} />}

      {flip7 && (() => {
        const player = players.find((p) => p.id === flip7.playerId)
        if (!player) return null
        return <Lucky7Overlay cards={player.hand} startPos={seatOfId(flip7.playerId)} />
      })()}

      {/* Action buttons */}
      <div className={`action-buttons ${showButtons ? 'visible' : 'hidden'}`} data-testid="action-buttons" data-visible={showButtons}>
        <SketchButton variant="primary" testId="hit" onClick={hit}>let it ride!</SketchButton>
        <SketchButton variant="ghost" testId="stay" onClick={stay} disabled={mustDraw}>go out</SketchButton>
      </div>

      {/* Inspection */}
      {inspectedCard && (
        <div onClick={() => setInspectedCard(null)} className="card-inspect-overlay" data-testid="card-inspect">
          <div className="card-inspect-pop">
            <PlayingCard card={inspectedCard} size="deck" />
          </div>
        </div>
      )}
    </div>
  )
}
