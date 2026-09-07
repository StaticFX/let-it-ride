import type { CSSProperties } from 'react'
import type { Card } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * A gambler card that somebody else stopped.
 *
 * The second beat of a counter, and it is a beat rather than part of the first
 * one on purpose: the card that did this has just turned over in the middle of
 * the table and is still being read. Striking out its victim in the same breath
 * would hand everybody the answer and the question at once, which is the thing
 * `PendingOutcome` was written to stop happening.
 *
 * It borrows the fizzle's animation outright — a card held up, struck through,
 * and dropped — because it is the same sentence: this one is not going to
 * happen. What differs is only why, and whether it is gone for good.
 */
export function CounteredCard({ card, name, returned, x, y, ms }: {
  card: Card
  /** Whoever played it, so the note says whose card was stopped. */
  name: string
  /** True when it goes back to the hand it came out of rather than being spent. */
  returned: boolean
  x: number
  y: number
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  return (
    <div
      className="fixed z-[216] pointer-events-none flex flex-col items-center gap-3 fizzle-note"
      data-testid="countered-card"
      data-card-def-id={card.defId}
      data-returned={returned}
      style={{ left: x, top: y, '--fizzle-dur': `${ms}ms` } as CSSProperties}
    >
      <div className="relative">
        <PlayingCard card={card} size="normal" dimmed />
        <div className="fizzle-strike bust-strike" />
      </div>
      <div className="fizzle-caption display text-xl -rotate-1">
        {returned ? `${name} gets it back` : `${name}'s card is spent`}
      </div>
    </div>
  )
}
