import type { CSSProperties } from 'react'

interface TitleMarkProps {
  /** The little word riding on the big one's shoulder. Optional. */
  small?: string
  big: string
  /**
   * Multiplies the whole lockup. Everything inside it is set in `em`, so one
   * number moves the letters, the outline, the extrude and the overlap together
   * — which is what keeps a heading at half size from being a wordmark with a
   * wildly heavy outline.
   */
  scale?: number
  className?: string
}

/**
 * The game's name, set the way a poster would set it.
 *
 * Two things make it read as printed rather than typed, and both are why this
 * is a component and not a `<h1>` with a text-shadow at the call site.
 *
 * The first is that the outline is drawn *behind* the letters instead of down
 * the middle of them. `-webkit-text-stroke` centres its stroke on the glyph
 * outline, so a six-pixel rule eats three pixels of every stem — which on a
 * handwriting face closes up the thin joins in `a`, `e` and `d` until the word
 * is a shape rather than a word. `paint-order` fixes exactly this and is not
 * dependable enough to hang the front door on, so the letters are set twice:
 * once underneath carrying the outline and the extrude, once on top carrying
 * only the fill. The copy underneath is `aria-hidden`, so what is read out is
 * still one word.
 *
 * The second is that the two lines are not stacked so much as leaned against
 * each other — the small one tucked left and turned one way, the big one
 * turned the other, overlapping by a fifth of its own height. A centred stack
 * of two rotated words looks like a mistake; a lockup that leans looks set.
 */
export function TitleMark({ small, big, scale = 1, className = '' }: TitleMarkProps) {
  return (
    <div
      className={`title-mark ${className}`}
      style={{ '--mark-scale': scale } as CSSProperties}
    >
      {small && (
        <span className="title-mark-line title-mark-small">
          <span className="title-mark-shell" aria-hidden="true">{small}</span>
          <span className="title-mark-face">{small}</span>
        </span>
      )}
      <span className="title-mark-line title-mark-big">
        <span className="title-mark-shell" aria-hidden="true">{big}</span>
        <span className="title-mark-face">{big}</span>
      </span>
    </div>
  )
}
