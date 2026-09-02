import { useState } from 'react'
import type { Card as CardType, Player } from '../../game/types'
import { useGame } from '../../hooks/useGame'
import { useWindowSize } from '../../hooks/useWindowSize'
import { findAction, useCatalog } from '../../state/gameStore'
import { RoughBox } from '../ui/RoughShapes'
import { PlayingCard } from '../cards/PlayingCard'
import { CardBack } from '../cards/CardBack'
import { DealtCard } from '../cards/DealtCard'
import { PlayerAvatar } from './PlayerAvatar'
import { Scoreboard } from './Scoreboard'
import { TurnClock } from './TurnClock'
import { SketchButton } from '../ui/Button'
import { SoundToggle } from '../ui/SoundToggle'
import { RoundIntro } from '../overlays/RoundIntro'
import { ImpactParticles } from '../overlays/ImpactParticles'
import { Lucky7Overlay } from '../overlays/Lucky7Overlay'
import { SlotMachine } from '../overlays/SlotMachine'
import { FreezeBurst } from '../overlays/FreezeBurst'
import { DrawThreeStamp } from '../overlays/DrawThreeStamp'
import { StolenCard } from '../overlays/StolenCard'

const SEAT_POSITIONS = [
  { left: '9%', top: '46%' },
  { left: '27%', top: '13%' },
  { left: '73%', top: '13%' },
  { left: '91%', top: '46%' },
]

export function GameBoard() {
  const game = useGame()
  const catalog = useCatalog()
  const { w, h } = useWindowSize()
  const [hoveredPlayerId, setHoveredPlayerId] = useState<string | null>(null)
  const [inspectedCard, setInspectedCard] = useState<CardType | null>(null)

  const {
    players, me, meIdx, others, currentPlayer, turnIndex, round, roundStartPlayer,
    deckCount, discardCount, localPlayerId, isMyTurn, isEliminated, mustDraw,
    isDealing, dealingPlayerId, pendingDef, isPickingTarget, pendingIsLocal, validTargets,
    targetChosen, pickTarget, hit, stay,
    animations, bust, steal, slots, dismissSlots, showRoundIntro, dismissRoundIntro, timer,
  } = game

  const deckCenter = { x: w / 2 - 20, y: h * 0.42 }

  function seatOf(playerIdx: number) {
    if (playerIdx === meIdx) return { x: w / 2, y: h - 120 }
    let seat = 0
    for (let i = 0; i < players.length; i++) {
      if (i === meIdx) continue
      if (i === playerIdx) break
      seat++
    }
    const pos = SEAT_POSITIONS[Math.min(seat, SEAT_POSITIONS.length - 1)]
    return { x: (parseFloat(pos.left) / 100) * w, y: (parseFloat(pos.top) / 100) * h }
  }

  function seatOfId(playerId: string) {
    return seatOf(players.findIndex((p) => p.id === playerId))
  }

  // ─── Animation lookups ───
  const hasScreenShake = animations.some((a) => a.type === 'screenShake')
  const impact = animations.find((a) => a.type === 'impact')
  const flip7 = animations.find((a) => a.type === 'flip7')
  const freezes = animations.filter((a) => a.type === 'freeze')
  const drawThrees = animations.filter((a) => a.type === 'drawThree')
  const fizzles = animations.filter((a) => a.type === 'fizzled')
  const secondChances = animations.filter((a) => a.type === 'secondChance')
  const timedOutIds = animations.filter((a) => a.type === 'timeout').map((a) => a.playerId)

  const showButtons = isMyTurn && !isEliminated && !isPickingTarget
  const canInspect = !isPickingTarget
  const myHovered = hoveredPlayerId === me?.id
  const mySpread = isMyTurn || myHovered

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

  function handCard(player: Player, card: CardType, idx: number, size: 'small' | 'normal', spread: boolean) {
    const role = bustRole(player.id, card.id)
    const scatter = scattering(player.id)
    const fanAngle = (idx - (player.hand.length - 1) / 2) * (spread ? (size === 'normal' ? 5 : 6) : 4)

    return (
      <div
        key={card.id}
        onClick={canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
        className="origin-bottom cursor-pointer"
        style={{
          marginLeft: idx === 0 ? 0 : size === 'normal' ? (spread ? -32 : -38) : spread ? -18 : -30,
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
      <div className="absolute top-7 left-[38px] z-[55] flex items-center gap-3">
        <h1 className="text-[38px] -rotate-[1.5deg] sway-slow">let it ride</h1>
        <SoundToggle className="mt-1.5" />
      </div>

      {/* Top-right */}
      <div className="absolute top-7 right-[38px] z-[55] text-right flex flex-col items-end gap-1">
        <small className="rotate-1 block">round {String(round).padStart(2, '0')}</small>
        <div className="display text-[30px] leading-none flex items-center gap-2.5 justify-end rotate-1 sway-mid">
          <span className="text-[var(--accent)]">→</span>
          {isPickingTarget
            ? players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? '...'
            : isDealing ? 'dealing…' : currentPlayer?.name || '...'}
        </div>
        <small className="rotate-1 block">
          {isPickingTarget
            ? pendingIsLocal ? 'pick a target!' : 'is picking a target…'
            : isDealing ? '' : isMyTurn ? 'your move!' : 'to act…'}
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
        const isActive = !isDealing && !isPickingTarget && pIndex === turnIndex && p.status === 'active'
        const isBeingDealt = dealingPlayerId === p.id
        const dimmed = (p.status === 'stayed' || p.status === 'bust') && !isBeingDealt
        const targetable = pendingIsLocal && !targetChosen && validTargets.includes(p.id)
        const targetHovered = targetable && hoveredPlayerId === p.id
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
              className={`flex flex-col items-center gap-2 ${isFrozen(p.id) ? 'frozen-seat' : ''}`}
              style={{ animation: impact?.targetId === p.id ? 'impactShake 500ms ease-out' : 'none' }}
            >
              <div className={`hover-glow ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
              <div className="flex items-center gap-2.5 relative z-[1]">
                <PlayerAvatar initial={p.name.charAt(0)} active={isActive || isBeingDealt || targetHovered} id={i + 1} />
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
                  </div>
                </div>
              </div>

              <div
                className="flex min-h-[86px] px-1.5 py-0.5 cursor-pointer origin-bottom transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                style={{ transform: spread ? 'scale(1.15)' : 'scale(1)' }}
              >
                {p.hand.map((card, idx) => handCard(p, card, idx, 'small', spread))}
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
        const targetable = pendingIsLocal && !targetChosen && validTargets.includes(me.id)
        const targetHovered = targetable && hoveredPlayerId === me.id
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
                onClick={targetable ? () => pickTarget(me.id) : undefined}
                onMouseEnter={() => setHoveredPlayerId(me.id)}
                onMouseLeave={() => setHoveredPlayerId((id) => (id === me.id ? null : id))}
                className={`flex items-end gap-[18px] px-4 py-2 relative transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)] ${isFrozen(me.id) ? 'frozen-seat' : ''}`}
                style={{
                  cursor: targetable ? 'crosshair' : 'default',
                  transform: targetHovered ? 'scale(1.05)' : 'scale(1)',
                }}
              >
                <div className={`hover-glow-self ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
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
                </div>

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
        const pos = targetChosen ? seatOfId(targetChosen) : { x: w / 2, y: h * 0.62 }
        const landed = !!targetChosen

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

      {steal && (
        <StolenCard
          card={steal.card}
          from={seatOfId(steal.fromPlayerId)}
          to={seatOfId(steal.toPlayerId)}
        />
      )}

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

      {slots && <SlotMachine card={slots.card} onDone={dismissSlots} />}

      {flip7 && (() => {
        const player = players.find((p) => p.id === flip7.playerId)
        if (!player) return null
        return <Lucky7Overlay cards={player.hand} startPos={seatOfId(flip7.playerId)} />
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
