import { useState } from 'react'
import type { Card as CardType, Player } from '../../game/types'
import { useGame } from '../../hooks/useGame'
import { useWindowSize } from '../../hooks/useWindowSize'
import { RoughBox } from '../ui/RoughShapes'
import { PlayingCard } from '../cards/PlayingCard'
import { CardBack } from '../cards/CardBack'
import { DealtCard } from '../cards/DealtCard'
import { PlayerAvatar } from './PlayerAvatar'
import { Scoreboard } from './Scoreboard'
import { TurnClock } from './TurnClock'
import { SketchButton } from '../ui/Button'
import { RoundIntro } from '../overlays/RoundIntro'
import { ImpactParticles } from '../overlays/ImpactParticles'
import { Lucky7Overlay } from '../overlays/Lucky7Overlay'
import { SlotMachine } from '../overlays/SlotMachine'

const SEAT_POSITIONS = [
  { left: '9%', top: '46%' },
  { left: '27%', top: '13%' },
  { left: '73%', top: '13%' },
  { left: '91%', top: '46%' },
]

export function GameBoard() {
  const game = useGame()
  const { w, h } = useWindowSize()
  const [hoveredPlayerId, setHoveredPlayerId] = useState<string | null>(null)
  const [inspectedCard, setInspectedCard] = useState<CardType | null>(null)

  const {
    players, me, meIdx, others, currentPlayer, turnIndex, round, roundStartPlayer,
    deckCount, discardCount, localPlayerId, isMyTurn, isEliminated, mustDraw,
    isDealing, dealingPlayerId, pendingDef, isPickingTarget, pendingIsLocal,
    targetChosen, pickTarget, hit, stay,
    animations, dismissAnimation, showRoundIntro, dismissRoundIntro, timer,
  } = game

  const deckCenter = { x: w / 2 - 20, y: h * 0.42 }

  function seatOf(playerIdx: number) {
    if (playerIdx === meIdx) return { x: w / 2, y: h - 100 }
    let seat = 0
    for (let i = 0; i < players.length; i++) {
      if (i === meIdx) continue
      if (i === playerIdx) break
      seat++
    }
    const pos = SEAT_POSITIONS[Math.min(seat, SEAT_POSITIONS.length - 1)]
    return { x: (parseFloat(pos.left) / 100) * w, y: (parseFloat(pos.top) / 100) * h }
  }

  // ─── Animation lookups ───
  const hasScreenShake = animations.some((a) => a.type === 'screenShake')
  const bustPlayerIds = animations.filter((a) => a.type === 'bust').map((a) => a.playerId)
  const impact = animations.find((a) => a.type === 'impact')
  const flip7 = animations.find((a) => a.type === 'flip7')
  const hasSlots = animations.some((a) => a.type === 'slots')
  const timedOutIds = animations.filter((a) => a.type === 'timeout').map((a) => a.playerId)

  const showButtons = isMyTurn && !isEliminated && !isPickingTarget
  const canInspect = !isPickingTarget
  const myHovered = hoveredPlayerId === me?.id
  const mySpread = isMyTurn || myHovered

  function statusBadge(player: Player) {
    if (player.status === 'bust') return <span className="status-badge text-[var(--accent)] border border-[var(--accent)]">bust!</span>
    if (player.status === 'stayed') return <span className="status-badge border border-[var(--ink)]">out</span>
    if (!player.connected) return <span className="status-badge border border-[var(--ink-soft)] text-[var(--ink-soft)]">away</span>
    return null
  }

  return (
    <div className={`game-shell ${hasScreenShake ? 'shake' : ''}`}>
      {showRoundIntro && (
        <RoundIntro
          round={round}
          startingPlayerName={players[roundStartPlayer]?.name ?? '...'}
          onDone={dismissRoundIntro}
        />
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
      <div className="absolute top-7 left-[38px] z-[55]">
        <h1 className="text-[38px] -rotate-[1.5deg] sway-slow">let it ride</h1>
      </div>

      {/* Top-right */}
      <div className="absolute top-7 right-[38px] z-[55] text-right flex flex-col items-end gap-1">
        <small className="rotate-1 block">round {String(round).padStart(2, '0')}</small>
        <div className="display text-[30px] leading-none flex items-center gap-2.5 justify-end rotate-1 sway-mid">
          <span className="text-[var(--accent)]">→</span>
          {isDealing ? 'dealing…' : currentPlayer?.name || '...'}
        </div>
        <small className="rotate-1 block">
          {isDealing ? '' : isMyTurn ? 'your move!' : isPickingTarget ? 'picking a target…' : 'to act…'}
        </small>
        {timer && <TurnClock timer={timer} />}
      </div>

      {/* Center piles */}
      <div className="absolute left-1/2 top-[42%] -translate-x-1/2 -translate-y-1/2 flex items-center gap-[38px] z-[4]">
        <div className={`relative ${isMyTurn ? 'cursor-pointer' : 'cursor-default'}`} onClick={isMyTurn ? hit : undefined}>
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
        <div className="relative w-[108px] h-[152px] flex items-center justify-center">
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
        const isActive = !isDealing && pIndex === turnIndex && p.status === 'active'
        const isBeingDealt = dealingPlayerId === p.id
        const dimmed = (p.status === 'stayed' || p.status === 'bust') && !isBeingDealt
        const targetable = isPickingTarget && pendingIsLocal && p.status === 'active' && !targetChosen
        const targetHovered = targetable && hoveredPlayerId === p.id
        const busting = bustPlayerIds.includes(p.id)
        const spread = hoveredPlayerId === p.id

        return (
          <div
            key={p.id}
            onClick={targetable ? () => pickTarget(p.id) : undefined}
            onMouseEnter={() => setHoveredPlayerId(p.id)}
            onMouseLeave={() => setHoveredPlayerId((id) => (id === p.id ? null : id))}
            style={{
              position: 'absolute',
              ...seatPos,
              transform: `translate(-50%, -50%) scale(${isBeingDealt ? 1.12 : targetHovered ? 1.15 : 1})`,
              zIndex: isActive || isBeingDealt || targetHovered ? 10 : targetable ? 5 : 3,
              opacity: dimmed ? 0.45 : isPickingTarget && !targetable ? 0.3 : 1,
              transition: 'opacity 280ms, transform 350ms cubic-bezier(.2,.9,.3,1.3)',
              cursor: targetable ? 'crosshair' : 'default',
            }}
          >
            <div
              className="flex flex-col items-center gap-2"
              style={{ animation: impact?.targetId === p.id ? 'impactShake 500ms ease-out' : 'none' }}
            >
              <div className={`hover-glow ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
              <div className="flex items-center gap-2.5 relative z-[1]">
                <PlayerAvatar initial={p.name.charAt(0)} active={isActive || isBeingDealt || targetHovered} id={i + 1} />
                <div className="text-left">
                  <div className="display text-[22px] leading-none relative">
                    {p.name}
                    {p.isBot && <small className="ml-1 font-normal">bot</small>}
                    {busting && <div className="bust-strike" />}
                  </div>
                  <div className="flex items-center gap-2 mt-0.5">
                    <span className={`number text-[22px] leading-none ${p.status === 'bust' ? 'text-[var(--accent)]' : ''}`}>
                      {p.handValue}
                    </span>
                    {statusBadge(p)}
                    {timedOutIds.includes(p.id) && <span className="status-badge border border-[var(--ink-soft)]">timed out</span>}
                  </div>
                </div>
              </div>

              <div
                className="flex min-h-[86px] px-1.5 py-0.5 cursor-pointer origin-bottom transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                style={{ transform: spread ? 'scale(1.15)' : 'scale(1)' }}
              >
                {p.hand.map((card, idx) => {
                  const fanAngle = (idx - (p.hand.length - 1) / 2) * (spread ? 6 : 4)
                  return (
                    <div
                      key={card.id}
                      onClick={canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
                      style={{
                        marginLeft: idx === 0 ? 0 : spread ? -18 : -30,
                        transform: `rotate(${fanAngle}deg) translateY(${Math.abs(fanAngle) * 0.7}px)`,
                        transformOrigin: 'bottom center',
                        transition: 'margin-left 280ms ease, transform 280ms cubic-bezier(.2,.9,.3,1.3)',
                      }}
                    >
                      <div
                        style={busting
                          ? { '--bust-spin': `${(idx % 2 === 0 ? -1 : 1) * (15 + idx * 5)}deg`, animation: `bustFlyUp 900ms ${idx * 80}ms cubic-bezier(.2,0,.6,1) forwards` } as React.CSSProperties
                          : {}}
                      >
                        <DealtCard card={card} from={deckCenter}>
                          <PlayingCard card={card} size="small" dimmed={dimmed && !busting} />
                        </DealtCard>
                      </div>
                    </div>
                  )
                })}
                {p.passives.length > 0 && (
                  <>
                    <div className="w-2.5 shrink-0" />
                    {p.passives.map((card, idx) => (
                      <div
                        key={card.id}
                        onClick={canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
                        className="card-fan-transition opacity-85 cursor-pointer"
                        style={{
                          marginLeft: idx === 0 ? 0 : -30,
                          transform: `rotate(${(idx - (p.passives.length - 1) / 2) * 3}deg)`,
                        }}
                      >
                        <DealtCard card={card} from={deckCenter}>
                          <PlayingCard card={card} size="small" dimmed={dimmed} />
                        </DealtCard>
                      </div>
                    ))}
                  </>
                )}
              </div>
            </div>
          </div>
        )
      })}

      {/* My hand */}
      {me && (() => {
        const selfTargetable = isPickingTarget && pendingIsLocal && me.status === 'active' && !targetChosen
        const selfTargetHovered = selfTargetable && hoveredPlayerId === me.id
        const busting = bustPlayerIds.includes(me.id)
        const dimmed = !isMyTurn && !isEliminated && !isDealing

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
                onClick={selfTargetable ? () => pickTarget(me.id) : undefined}
                onMouseEnter={() => setHoveredPlayerId(me.id)}
                onMouseLeave={() => setHoveredPlayerId((id) => (id === me.id ? null : id))}
                className="flex items-end gap-[18px] px-4 py-2 relative transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                style={{
                  cursor: selfTargetable ? 'crosshair' : 'default',
                  transform: selfTargetHovered ? 'scale(1.05)' : 'scale(1)',
                }}
              >
                <div className={`hover-glow-self ${selfTargetHovered ? 'opacity-100' : 'opacity-0'}`} />
                <div className="flex flex-col items-end gap-1.5 min-w-[120px]">
                  <div
                    className="flex items-center gap-2.5 transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                    style={{ transform: `scale(${dealingPlayerId === me.id ? 1.1 : 1})` }}
                  >
                    <PlayerAvatar
                      initial={me.name.charAt(0)}
                      active={(isMyTurn && !isEliminated) || dealingPlayerId === me.id}
                      id={0}
                    />
                    <div>
                      <div className="display text-[26px] leading-none relative">
                        {me.name}
                        {busting && <div className="bust-strike" />}
                      </div>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <span className={`number text-[28px] leading-none ${me.status === 'bust' ? 'text-[var(--accent)]' : ''}`}>
                          {me.handValue}
                        </span>
                        {statusBadge(me)}
                      </div>
                    </div>
                  </div>
                </div>

                <div
                  className="flex items-end px-3 origin-bottom"
                  style={{
                    minHeight: mySpread ? 180 : 110,
                    transform: mySpread ? 'scale(1)' : dealingPlayerId === me.id ? 'scale(0.9)' : 'scale(0.75)',
                    opacity: busting ? 1 : isEliminated ? 0.4 : dimmed && !myHovered ? 0.55 : 1,
                    transition:
                      'transform 420ms cubic-bezier(.2,.9,.3,1.3), opacity 320ms ease, min-height 420ms cubic-bezier(.2,.9,.3,1.3)',
                    filter: dimmed && !isEliminated && !myHovered ? 'grayscale(0.6)' : 'none',
                  }}
                >
                  {me.hand.map((card, idx) => {
                    const fanAngle = (idx - (me.hand.length - 1) / 2) * (mySpread ? 5 : 4)
                    return (
                      <div
                        key={card.id}
                        onClick={canInspect ? () => setInspectedCard(card) : undefined}
                        className="origin-bottom cursor-pointer"
                        style={{
                          marginLeft: idx === 0 ? 0 : mySpread ? -32 : -38,
                          transform: `rotate(${fanAngle}deg) translateY(${Math.abs(fanAngle) * 0.7}px)`,
                          transition: 'transform 320ms cubic-bezier(.2,.9,.3,1.4), margin-left 320ms ease',
                        }}
                      >
                        <div
                          style={busting
                            ? { '--bust-spin': `${(idx % 2 === 0 ? -1 : 1) * (15 + idx * 5)}deg`, animation: `bustFlyUp 900ms ${idx * 80}ms cubic-bezier(.2,0,.6,1) forwards` } as React.CSSProperties
                            : {}}
                        >
                          <DealtCard card={card} from={deckCenter}>
                            <PlayingCard card={card} size={mySpread ? 'normal' : 'small'} />
                          </DealtCard>
                        </div>
                      </div>
                    )
                  })}
                  {me.passives.length > 0 && (
                    <>
                      <div className="shrink-0" style={{ width: mySpread ? 18 : 12 }} />
                      {me.passives.map((card, idx) => (
                        <div
                          key={card.id}
                          onClick={canInspect ? () => setInspectedCard(card) : undefined}
                          className="card-fan-transition-slow opacity-85 cursor-pointer"
                          style={{
                            marginLeft: idx === 0 ? 0 : -38,
                            transform: `rotate(${(idx - (me.passives.length - 1) / 2) * 3}deg)`,
                          }}
                        >
                          <DealtCard card={card} from={deckCenter}>
                            <PlayingCard card={card} size="small" />
                          </DealtCard>
                        </div>
                      ))}
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        )
      })()}

      {/* Scoreboard */}
      <div className="absolute left-[38px] bottom-9 z-[90]">
        <Scoreboard players={players} currentPlayerId={currentPlayer?.id || ''} localPlayerId={localPlayerId} />
      </div>

      {/* The card waiting for a target */}
      {isPickingTarget && pendingDef && (() => {
        const chosenIdx = targetChosen ? players.findIndex((p) => p.id === targetChosen) : -1
        const pos = chosenIdx >= 0 ? seatOf(chosenIdx) : { x: w / 2, y: h * 0.62 }
        const landed = chosenIdx >= 0

        return (
          <div
            className="fixed z-[200] pointer-events-none flex flex-col items-center gap-2.5"
            style={{
              left: pos.x,
              top: landed ? pos.y - 60 : pos.y,
              transform: `translate(-50%, -50%) rotate(${landed ? -12 : 0}deg) scale(${landed ? 0.65 : 1.8})`,
              transition: 'left 500ms cubic-bezier(.2,.9,.3,1.3), top 500ms cubic-bezier(.2,.9,.3,1.3), transform 500ms cubic-bezier(.2,.9,.3,1.3)',
            }}
          >
            <div style={{ animation: landed ? 'none' : 'swayMore 1.8s ease-in-out infinite' }}>
              <PlayingCard
                card={{ id: 'pending', kind: 'action', label: pendingDef.name, value: 0, defId: pendingDef.id }}
                size="deck"
              />
            </div>
            {pendingIsLocal && !targetChosen && <div className="pick-target-label">pick a target!</div>}
            {!pendingIsLocal && (
              <div className="pick-target-label">
                {players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? 'someone'} is picking…
              </div>
            )}
          </div>
        )
      })()}

      {/* Impact */}
      {impact && (() => {
        const idx = players.findIndex((p) => p.id === impact.targetId)
        if (idx < 0) return null
        const pos = seatOf(idx)
        return <ImpactParticles x={pos.x} y={pos.y - 60} />
      })()}

      {hasSlots && <SlotMachine onDone={() => dismissAnimation('slots')} />}

      {flip7 && (() => {
        const player = players.find((p) => p.id === flip7.playerId)
        if (!player) return null
        const idx = players.findIndex((p) => p.id === flip7.playerId)
        return <Lucky7Overlay cards={player.hand} startPos={seatOf(idx)} />
      })()}

      {/* Action buttons */}
      <div className={`action-buttons ${showButtons ? 'visible' : 'hidden'}`}>
        <SketchButton variant="primary" onClick={hit}>let it ride!</SketchButton>
        <SketchButton variant="ghost" onClick={stay} disabled={mustDraw}>go out</SketchButton>
      </div>

      {/* Inspection */}
      {inspectedCard && (
        <div onClick={() => setInspectedCard(null)} className="card-inspect-overlay">
          <div className="card-inspect-pop">
            <PlayingCard card={inspectedCard} size="deck" />
          </div>
        </div>
      )}
    </div>
  )
}
