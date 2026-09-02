import { useLayoutEffect, useRef } from 'react'
import type { Card } from '../../game/types'
import { markDealt } from './dealtCards'

/**
 * Slides a card in from the draw pile the first time it appears on the table.
 *
 * The server is the one that decides what gets drawn, so instead of animating a
 * separate card across the screen and then adding it to the hand, the real card
 * is laid out where it belongs and then played backwards from the deck. Any
 * later re-render leaves it exactly where it is.
 */
interface DealtCardProps {
  card: Card
  /** Screen coordinates of the draw pile. */
  from: { x: number; y: number }
  durationMs?: number
  children: React.ReactNode
}

export function DealtCard({ card, from, durationMs = 520, children }: DealtCardProps) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element || !markDealt(card.id)) return

    const box = element.getBoundingClientRect()
    const dx = from.x - (box.left + box.width / 2)
    const dy = from.y - (box.top + box.height / 2)

    element.style.transition = 'none'
    element.style.transform = `translate(${dx}px, ${dy}px) scale(1.25) rotate(-6deg)`
    element.style.opacity = '0.85'

    const frame = requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        element.style.transition =
          `transform ${durationMs}ms cubic-bezier(.2,.9,.3,1.15), opacity ${durationMs / 3}ms ease-out`
        element.style.transform = ''
        element.style.opacity = ''
      }),
    )
    return () => cancelAnimationFrame(frame)
  }, [card.id, from.x, from.y, durationMs])

  return <div ref={ref}>{children}</div>
}
