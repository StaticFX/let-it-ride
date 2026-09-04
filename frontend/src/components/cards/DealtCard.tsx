import { useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import type { Card } from '../../game/types'
import { CardBack } from './CardBack'
import { dealsTense, hasDealt, markDealt } from './dealtCards'

/**
 * Slides a card in from the draw pile the first time it appears on the table.
 *
 * The server is the one that decides what gets drawn, so instead of animating a
 * separate card across the screen and then adding it to the hand, the real card
 * is laid out where it belongs and then played backwards from the deck. Any
 * later re-render leaves it exactly where it is.
 *
 * One card in ten arrives the slow way instead — drawn out face down, carried
 * over the hand and then flipped and slammed down. Which cards those are is
 * rolled off the id rather than at render time (see [dealsTense]), because a
 * roll that changes under a re-render changes the animation mid-flight. The
 * flight itself is `tension-deal` in index.css.
 */
interface DealtCardProps {
  card: Card
  /** Screen coordinates of the draw pile. */
  from: { x: number; y: number }
  durationMs?: number
  children: React.ReactNode
}

type BackSize = 'small' | 'normal' | 'deck'

/**
 * Card widths, from [CardBack]'s own DIMS. The back is laid over the face for
 * the flight, so it has to be drawn at the size of the card it covers, and the
 * card only says how wide it is once it is on the table.
 */
const BACK_WIDTHS: ReadonlyArray<readonly [BackSize, number]> = [
  ['small', 52],
  ['normal', 92],
  ['deck', 100],
]

/** The back that covers a face [width] wide most closely. */
function backSizeFor(width: number): BackSize {
  let best = BACK_WIDTHS[1]
  for (const candidate of BACK_WIDTHS) {
    if (Math.abs(candidate[1] - width) < Math.abs(best[1] - width)) best = candidate
  }
  return best[0]
}

/** A tension entrance in flight: where it starts, and how big its back is. */
interface Tension {
  /** Offset from where the card belongs back to the draw pile, in px. */
  dx: number
  dy: number
  size: BackSize
}

export function DealtCard({ card, from, durationMs = 520, children }: DealtCardProps) {
  const ref = useRef<HTMLDivElement>(null)
  // Set from the layout effect rather than from render: the back has to be over
  // the face before the browser paints the card for the first time, or the
  // player is shown the card the flight is meant to be hiding.
  const [tension, setTension] = useState<Tension | null>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element || hasDealt(card.id)) return

    const box = element.getBoundingClientRect()
    const dx = from.x - (box.left + box.width / 2)
    const dy = from.y - (box.top + box.height / 2)

    if (dealsTense(card.id)) {
      // The face is the first child; its own box is the card's, where this
      // wrapper's is whatever the fan gives it.
      const face = element.firstElementChild as HTMLElement | null
      // The flight is a CSS animation, and it starts the moment the class lands
      // — all the frames are for is the marking, on the same terms as below.
      let tenseFrame = requestAnimationFrame(() => {
        tenseFrame = requestAnimationFrame(() => markDealt(card.id))
      })
      setTension({ dx, dy, size: backSizeFor(face?.offsetWidth || box.width) })
      // The back comes off once the card has landed. Left up it costs a rough
      // redraw on every boil tick for as long as the card is on the table, and
      // the flip has already turned it away from the player by then.
      const landed = window.setTimeout(() => setTension(null), durationMs + 80)
      return () => {
        cancelAnimationFrame(tenseFrame)
        window.clearTimeout(landed)
        setTension(null)
      }
    }

    /** Puts the card back where the layout wants it, animated or not. */
    const settle = () => {
      element.style.transform = ''
      element.style.opacity = ''
    }

    element.style.transition = 'none'
    element.style.transform = `translate(${dx}px, ${dy}px) scale(1.25) rotate(-6deg)`
    element.style.opacity = '0.85'

    // Reassigned rather than shadowed: cancelling the outer frame alone leaves
    // the inner one to run on a card that has been torn down, marking an
    // entrance seen that was never played.
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        // Only now has the entrance been seen, so a setup that is torn down
        // before this frame gets to try again.
        markDealt(card.id)
        element.style.transition =
          `transform ${durationMs}ms cubic-bezier(.2,.9,.3,1.15), opacity ${durationMs / 3}ms ease-out`
        settle()
      })
    })

    // Dropping the frame is not enough: the card has already been moved to the
    // deck by this point, and leaving it there is how it ends up parked on the
    // draw pile for good. Strict mode tears every effect down once on mount.
    return () => {
      cancelAnimationFrame(frame)
      // Nothing on this element transitions on its own, so clearing the
      // override snaps rather than animates.
      element.style.transition = ''
      settle()
    }
  }, [card.id, from.x, from.y, durationMs])

  return (
    <div
      ref={ref}
      className={tension ? 'tension-deal' : undefined}
      style={
        tension
          ? ({
              '--tension-dx': `${tension.dx}px`,
              '--tension-dy': `${tension.dy}px`,
              '--tension-dur': `${durationMs}ms`,
            } as CSSProperties)
          : undefined
      }
    >
      {children}
      {tension && (
        <div className="tension-deal-back" aria-hidden="true">
          {/* The sway is the card's own idle; a card in flight has its own motion. */}
          <CardBack size={tension.size} style={{ animation: 'none' }} />
        </div>
      )}
    </div>
  )
}
