import type { CSSProperties } from 'react'
import type { Card } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/** The largest face `PlayingCard` draws, which everything here is scaled off. */
const DECK_W = 100
const DECK_H = 142

/**
 * How much bigger than a card on the table the reveal is drawn.
 *
 * A card nobody has seen is blown up further than one they have, and for the
 * same reason it is held longer: the description is set relative to the card,
 * so a card that has to be *read* has to be physically bigger. At 1.75 the
 * description lands around sixteen pixels, which is a sentence somebody across
 * a table can take in; at deck size it is nine, which is a sentence they can
 * see is there.
 */
const SCALE_FIRST = 1.75
const SCALE_KNOWN = 1.3

/**
 * A gambler card coming out of a hidden hand and turning face up.
 *
 * This is the mode's whole signature — the moment the table finds out what
 * somebody has been carrying — so it is the one animation in the game worth
 * spending real time on, and the only one whose length depends on who is
 * watching rather than on what happened.
 *
 * It takes the middle of the table on a sheet of its own, the way a showdown
 * does, rather than being placed on the felt somewhere. Placing it meant it
 * landed over the deck and the discard pile, and a card you are being asked to
 * read for the first time cannot be printed over two other things.
 *
 * A card nobody at this table has seen is held for about four seconds, at the
 * largest size the face has. That is not indulgence: every other number in
 * `ANIMATION_TTL_MS` was tuned for a card you already know, where the animation
 * only has to say *who*. This one has to say who, what, and what that even
 * means, to several people at once who have never seen the word before.
 *
 * The fifth one of the evening gets a beat and no more — see `firstSeen`, which
 * is the *table's* answer and not this browser's. Whoever played the card owns
 * the animation gate, and they are the one person who certainly knows what it
 * does; a client that only slowed down for cards it personally had not seen
 * would let them wave it past everybody else.
 */
export function GamblerReveal({ card, name, ms, firstSeen }: {
  card: Card
  /** Whose card it was, said out loud — a hidden hand has no seat to fly from. */
  name: string
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
  /** Whether this table has seen this card before. */
  firstSeen: boolean
}) {
  const scale = firstSeen ? SCALE_FIRST : SCALE_KNOWN
  return (
    <div
      className="fixed inset-0 z-[218] flex items-center justify-center pointer-events-none gambler-reveal"
      data-testid="gambler-reveal"
      data-card-def-id={card.defId}
      data-first-seen={firstSeen}
      style={{ '--reveal-dur': `${ms}ms` } as CSSProperties}
    >
      <div className="showdown-sheet flex flex-col items-center gap-3 px-8 py-6">
        <div className="display text-xl -rotate-1 text-[var(--ink-soft)]">
          {name} had this all along
        </div>
        {/* The box reserves the room the scaled card takes up; the card itself
            is scaled inside it, so the caption above and the line below are not
            printed across a face that grew under them. */}
        <div
          className="relative flex items-center justify-center"
          style={{ width: DECK_W * scale, height: DECK_H * scale }}
        >
          {/* The size is here and the turning-over is on the element inside, so
              that reduced motion drops the flip and keeps the card big. */}
          <div style={{ transform: `scale(${scale})` }}>
            <div className="gambler-reveal-card">
              <PlayingCard card={card} size="deck" />
            </div>
          </div>
        </div>
        {/* Only the first time, and said about the table rather than about the
            reader: whoever played it has plainly seen it before, and this line
            goes to everybody. Told twice it is not news either, and a hint that
            never goes away is a hint nobody reads. */}
        {firstSeen && <small className="text-[var(--ink-soft)]">new to this table</small>}
      </div>
    </div>
  )
}
