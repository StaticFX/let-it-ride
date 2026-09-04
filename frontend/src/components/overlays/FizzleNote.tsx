import type { CSSProperties } from 'react'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * A card that turned out to be able to do nothing.
 *
 * This used to be a line of text in the middle of the felt, gone in a second
 * and a half, and it was the one thing at the table nothing else explained: a
 * card was drawn, the card vanished, another one came. So it is shown instead
 * — the card is held up, struck through, and dropped, and the replacement is
 * announced before it arrives. It is deliberately the slowest small animation
 * in the game, because it is the only one that has to be read to be understood.
 */
export function FizzleNote({ name, cardDefId, reason, x, y, ms }: {
  /** What the card was called, so the note names it rather than gesturing at it. */
  name: string
  cardDefId: string
  /** Why it could not be played, in the table's own voice. */
  reason: string
  x: number
  y: number
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  return (
    <div
      className="fixed z-[215] pointer-events-none flex flex-col items-center gap-3 fizzle-note"
      data-testid="fizzle-note"
      data-card-def-id={cardDefId}
      style={{ left: x, top: y, '--fizzle-dur': `${ms}ms` } as CSSProperties}
    >
      <div className="relative fizzle-card">
        <PlayingCard
          card={{ id: `fizzle-${cardDefId}`, kind: 'action', label: name, value: 0, defId: cardDefId }}
          size="deck"
        />
        {/* Struck out by hand, after the card has been held up long enough to
            see what it was. */}
        <svg className="fizzle-strike" viewBox="0 0 108 152" fill="none" aria-hidden="true">
          <path
            d="M12 18 q26 24 42 52 q18 32 42 62"
            stroke="var(--accent)"
            strokeWidth="5"
            strokeLinecap="round"
          />
          <path
            d="M95 20 q-24 26 -40 54 q-18 30 -42 58"
            stroke="var(--accent)"
            strokeWidth="5"
            strokeLinecap="round"
          />
        </svg>
      </div>

      <div className="text-center fizzle-caption">
        <div className="display text-[24px] font-bold text-[var(--accent)] whitespace-nowrap">{reason}</div>
        <small>drawing a replacement…</small>
      </div>
    </div>
  )
}
