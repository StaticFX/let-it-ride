import type { CSSProperties } from 'react'
import type { Card } from '../../game/types'
import { findPassive, useCatalog } from '../../state/gameStore'
import { theme } from '../../theme'
import { PlayingCard } from '../cards/PlayingCard'
import { RoughCircle } from '../ui/RoughShapes'

/** The largest face `PlayingCard` draws, which everything here is placed off. */
const DECK_W = 100
const DECK_H = 142

/**
 * A second life being spent.
 *
 * This was a line of green text above a seat for most of this game's life, and
 * it was the least honest animation at the table: three cards moved and it named
 * none of them. The duplicate that would have ended the round is discarded
 * inside the same transition, so it never appears in the hand at all — a card
 * was drawn and simply did not arrive. The card that stopped it leaves the
 * modifier row in that same transition, so what the player actually saw was
 * something they owned disappearing for no stated reason. The caption said
 * "second life!" and left the rest to be worked out.
 *
 * So it is played out instead. The duplicate is carried up into the middle of
 * the screen and held there, big, beside the card it collided with, long enough
 * to read what would have happened. The second life comes up out of its owner's
 * row, tears it in half, and goes up and out of the top of the screen — spent,
 * and gone with it.
 *
 * Every beat is a fraction of `ms`, so the animation and the hold the table is
 * under can never say two different numbers — see `GameAnimation.ms`. The one
 * frame worth knowing about outside this file is the tear at 58%, which is
 * `SECOND_LIFE_RIP_MS` and is where the sound goes.
 */
export function SecondLife({ card, matched, saver, from, center, ms }: {
  /** The duplicate that would have busted them. */
  card: Card
  /** What it collided with, when there was one — a threshold bust has none. */
  matched?: Card
  /**
   * The card that was spent stopping it. Older servers do not send it, and the
   * duplicate is then torn by nothing visible rather than by the wrong card —
   * a face guessed at from a def id would be a face this client had decided on.
   */
  saver?: Card
  /** The seat it all belongs to, so the cards come out of their own hand. */
  from: { x: number; y: number }
  /** Where this sheet centres itself, which is what `from` is measured against. */
  center: { x: number; y: number }
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  // The stage is a point at the centre of the viewport and everything is hung
  // off it, so the two halves of a torn card stay one card however the sheet is
  // sized. The seat arrives as a delta from that point rather than as a place:
  // this is the one thing at the table that stops being about a seat.
  const style = {
    '--life-dur': `${ms}ms`,
    '--life-dx': `${from.x - center.x}px`,
    '--life-dy': `${from.y - center.y}px`,
  } as CSSProperties

  // The halo and the word are struck in the saving card's own ink, out of the
  // catalog, the same place [PlayingCard] gets it from — a colour picked here
  // would be this client deciding what a card looks like, and would go on being
  // whatever it was decided to be after the card was recoloured.
  const catalog = useCatalog()
  const accent = (saver && findPassive(catalog, saver.defId)?.accent) ?? theme.passiveAccent

  const box: CSSProperties = {
    position: 'absolute',
    left: -DECK_W / 2,
    top: -DECK_H / 2,
    width: DECK_W,
    height: DECK_H,
  }

  return (
    <div
      className="fixed inset-0 z-[222] flex items-center justify-center pointer-events-none second-life"
      data-testid="second-life"
      data-card-id={card.id}
      style={style}
    >
      <div className="second-life-wash" />

      <div className="relative w-0 h-0">
        {/* What they already had. Behind and to the side rather than beside:
            the duplicate is the card this is about, and the pair only has to
            read as a pair. */}
        {matched && (
          <div className="second-life-echo" style={box}>
            <PlayingCard card={matched} size="deck" style={{ animation: 'none' }} />
          </div>
        )}

        {/* The duplicate, drawn twice and clipped down the middle, so the tear
            is one card coming apart rather than two cards moving. The jag in the
            two polygons is the same line read from either side. */}
        <div className="second-life-doomed">
          <div className="second-life-half second-life-half-l" style={box}>
            <PlayingCard card={card} size="deck" style={{ animation: 'none' }} />
          </div>
          <div className="second-life-half second-life-half-r" style={box}>
            <PlayingCard card={card} size="deck" style={{ animation: 'none' }} />
          </div>
        </div>

        {/* The table's own words for a clash, so a save and a bust say the same
            thing about the same thing. */}
        {matched && (
          <div className="second-life-clash display font-bold text-[var(--accent)] whitespace-nowrap">
            same {matched.label}!
          </div>
        )}

        {saver && (
          <div className="second-life-saver" style={box}>
            {/* Drawn over the card rather than round it: a halo is the one thing
                that says "angel" without anybody having to be told. */}
            <div className="second-life-halo">
              <RoughCircle size={78} stroke={accent} strokeWidth={2.4} roughness={2.2} />
            </div>
            <PlayingCard card={saver} size="deck" style={{ animation: 'none' }} />
          </div>
        )}

        {/* Named off the card rather than written down here — what it is called
            is the catalog's to say. A server too old to send the card gets the
            plain fact instead. */}
        <div className="second-life-word display font-bold whitespace-nowrap" style={{ color: accent }}>
          {saver ? `${saver.label}!` : 'saved!'}
        </div>
      </div>
    </div>
  )
}
