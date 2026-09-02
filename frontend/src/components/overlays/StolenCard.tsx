import { useLayoutEffect, useRef } from 'react'
import type { Card } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * Steal used to be invisible: a card silently moved between two hands and the
 * only clue was the totals changing. Now the actual card is shown leaving the
 * victim's seat and landing in the thief's.
 */
export function StolenCard({ card, from, to }: {
  card: Card
  from: { x: number; y: number }
  to: { x: number; y: number }
}) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const frame = requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        element.style.left = `${to.x}px`
        element.style.top = `${to.y}px`
        element.style.transform = 'translate(-50%, -50%) scale(0.75) rotate(14deg)'
      }),
    )
    return () => cancelAnimationFrame(frame)
  }, [to.x, to.y])

  return (
    <div
      ref={ref}
      className="fixed z-[220] pointer-events-none flex flex-col items-center gap-1.5 transition-all duration-[850ms] ease-[cubic-bezier(.3,.9,.3,1.15)]"
      style={{
        left: from.x,
        top: from.y,
        transform: 'translate(-50%, -50%) scale(1.35) rotate(-10deg)',
      }}
    >
      <PlayingCard card={card} size="normal" />
      <div className="display text-lg font-bold text-[var(--accent)] steal-label">stolen!</div>
    </div>
  )
}
