import { useState, useEffect } from 'react'
import { theme } from '../../theme'
import { PlayingCard } from '../cards/PlayingCard'
import type { Card } from '../../game/types'

export function Lucky7Overlay({ cards, startPos }: { cards: Card[]; startPos: { x: number; y: number } }) {
  const [phase, setPhase] = useState<'init' | 'flying' | 'dancing'>('init')

  useEffect(() => {
    requestAnimationFrame(() => requestAnimationFrame(() => setPhase('flying')))
    const timer = setTimeout(() => setPhase('dancing'), 1100)
    return () => clearTimeout(timer)
  }, [])

  const cx = window.innerWidth / 2
  const cy = window.innerHeight * 0.4

  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 300, pointerEvents: 'none' }}>
      <div style={{
        position: 'absolute', inset: 0,
        background: 'rgba(246, 241, 227, 0.35)',
        opacity: phase !== 'init' ? 1 : 0,
        transition: 'opacity 600ms ease',
      }} />
      {cards.map((card, idx) => {
        const fanAngle = (idx - (cards.length - 1) / 2) * 10
        const fanOffsetX = (idx - (cards.length - 1) / 2) * 34
        const arrived = phase === 'flying' || phase === 'dancing'
        return (
          <div key={card.id} style={{
            position: 'fixed',
            left: arrived ? cx + fanOffsetX : startPos.x,
            top: arrived ? cy : startPos.y,
            transform: 'translate(-50%, -50%)',
            transition: phase === 'flying'
              ? `left 800ms ${idx * 60}ms cubic-bezier(.2,.9,.3,1.3), top 800ms ${idx * 60}ms cubic-bezier(.2,.9,.3,1.3)`
              : 'none',
            zIndex: 300 + idx,
          }}>
            <div style={{
              transform: `rotate(${arrived ? fanAngle : 0}deg)`,
              transition: phase === 'flying' ? `transform 800ms ${idx * 60}ms cubic-bezier(.2,.9,.3,1.3)` : 'none',
            }}>
              <div style={{
                animation: phase === 'dancing' ? `lucky7Dance 1.2s ${idx * 100}ms ease-in-out infinite` : 'none',
              }}>
                <PlayingCard card={card} size="normal" />
              </div>
            </div>
          </div>
        )
      })}
      {phase === 'dancing' && (
        <div style={{
          position: 'fixed', left: '50%', top: cy - 110,
          transform: 'translate(-50%, -50%)',
          fontFamily: theme.fontDisplay, fontSize: 48, fontWeight: 700,
          color: theme.actionAccent,
          animation: 'swayMore 2s ease-in-out infinite',
          zIndex: 310,
          textShadow: '2px 2px 0 rgba(255,255,255,0.8)',
        }}>lucky 7!</div>
      )}
    </div>
  )
}
