import type { Card } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/** The three throws, as they are written on the table. */
const THROW_MARKS: Record<string, string> = {
  rock: '✊',
  paper: '✋',
  scissors: '✌',
}

interface Side {
  name: string
  /** A throw, for the comeback; a card, for the all in. */
  label?: string
  card?: Card
  /** Struck through in the accent, the way a losing hand is. */
  lost?: boolean
}

/**
 * Everything that was answered in secret, turned over at once.
 *
 * Both cards this draws for — the comeback's two throws and the all in's whole
 * table of bets — are built the same way round: nobody sees anything until
 * everybody has answered, and then all of it lands together. That moment is the
 * card, so it gets the middle of the table and a beat of its own.
 */
export function Showdown({ title, sides, footnote }: {
  title: string
  sides: Side[]
  footnote?: string
}) {
  return (
    <div
      className="fixed inset-0 z-[230] flex flex-col items-center justify-center pointer-events-none showdown"
      data-testid="showdown"
      data-title={title}
    >
      <div className="showdown-sheet flex flex-col items-center gap-3 px-8 py-6">
        <div className="display text-[30px] font-bold text-[var(--accent)] -rotate-1">{title}</div>

        <div className="flex items-end justify-center gap-5 flex-wrap max-w-[560px]">
          {sides.map((side, i) => (
            <div
              key={`${side.name}-${i}`}
              data-testid="showdown-side"
              data-name={side.name}
              data-lost={side.lost ?? false}
              className="flex flex-col items-center gap-1.5"
              style={{
                animation: `showdownLand 420ms cubic-bezier(.2,.9,.3,1.35) ${i * 110}ms both`,
                opacity: side.lost ? 0.75 : 1,
              }}
            >
              {side.card
                ? <PlayingCard card={side.card} size="small" />
                : <div className="text-[44px] leading-none">{THROW_MARKS[side.label ?? ''] ?? side.label}</div>}
              <div
                className={`display text-lg leading-none ${side.lost ? 'text-[var(--accent)] line-through' : ''}`}
              >
                {side.name}
              </div>
            </div>
          ))}
        </div>

        {footnote && <div className="text-muted text-center max-w-[420px] leading-snug">{footnote}</div>}
      </div>
    </div>
  )
}
