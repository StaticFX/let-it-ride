import type { ResponseFrame } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * The cards in flight, as a pile.
 *
 * Rendered inside the block that already holds a card being answered, so
 * everything the end-to-end suite reaches for is where it was. What is new is
 * that the block now contains a *stack* rather than a card — and that is the
 * whole read on "cards answer cards": the pile literally gets taller, each card
 * leaning a little further, and the one on top is the one being asked about.
 *
 * Drawn oldest-at-the-back, which is the way it was played and the way the
 * table watched it happen. A card somebody has already stopped is struck
 * through with the same mark a busted seat gets, because it means the same
 * thing: that one is not going to happen.
 */
export function ResponseStack({ frames, x, y }: {
  /** Oldest first. The last of them is the card the table is answering. */
  frames: ResponseFrame[]
  x: number
  y: number
}) {
  return (
    <div
      className="fixed z-[200] -translate-x-1/2 -translate-y-1/2 pointer-events-none"
      style={{ left: x, top: y }}
      data-testid="response-stack"
      data-depth={frames.length}
    >
      <div className="relative flex items-center justify-center" style={{ width: 190, height: 210 }}>
        {frames.map((frame, idx) => {
          // Each one sits well up and across from the one under it, leaning
          // further as it goes. Generously: the first version offset them by
          // seven pixels and a pile of two read as one card with a thick edge,
          // which is the one thing this component exists to say.
          const lift = idx * 30
          const tilt = -7 + idx * 9
          const top = idx === frames.length - 1
          return (
            <div
              key={frame.id}
              data-testid="stack-card"
              data-card-def-id={frame.cardDefId}
              data-player-id={frame.playerId}
              data-index={idx}
              data-countered={!!frame.cancelled}
              className="absolute"
              style={{
                transform: `translate(${-lift * 0.6}px, ${-lift}px) rotate(${tilt}deg) scale(${top ? 1 : 0.9})`,
                zIndex: idx,
                // The ones underneath step back so the question on top is the
                // thing being read, without them being hidden — you have to be
                // able to count the pile.
                opacity: top ? 1 : 0.75,
                transition: 'transform 260ms cubic-bezier(.2,.9,.3,1.3), opacity 200ms ease',
              }}
            >
              <div className={frame.cancelled ? 'relative' : undefined}>
                <PlayingCard card={frame.card} size="normal" dimmed={!!frame.cancelled} />
                {frame.cancelled && <div className="bust-strike" />}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
