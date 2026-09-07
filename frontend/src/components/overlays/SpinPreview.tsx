import type { CSSProperties } from 'react'
import { RoughCircle } from '../ui/RoughShapes'

/**
 * The card that asks which way the table turns — see `SPIN_TABLE`.
 *
 * A def id in the client, which is only ever allowed for a picture: nothing
 * here decides anything, it draws the answer to "which way?" while the drawer
 * is still deciding. The rule, the options and what actually happens are all
 * the server's.
 */
export const SPIN_TABLE_DEF_ID = 'spinTable'

/** How wide the ring is drawn, and how far out from the middle the heads sit. */
const RING = 300
const RADIUS = RING / 2 - 6

/**
 * Where the arrowheads sit on the ring, in degrees clockwise from the top.
 *
 * Three rather than one, and none of them at the bottom: the card being played
 * is held up over the lower half of the felt, so a single head has a fair
 * chance of being behind it. Three round the ring cannot all be.
 */
const HEADS = [20, 140, 260]

/**
 * A ghost of the spin, drawn round the deck while the drawer hovers a
 * direction.
 *
 * "left" and "right" on two buttons say nothing about a round table — which way
 * a hand actually travels is a fact about the seats, not about the words — so
 * the question gets answered in the shape the answer comes in. The ring turns
 * the way the table would, round the middle of the felt where the deck sits,
 * and goes when the cursor does: it costs nothing to look, which is the point
 * of showing it before the click rather than after.
 */
export function SpinPreview({ direction, x, y }: { direction: string; x: number; y: number }) {
  const left = direction === 'left'

  return (
    <div
      className="fixed z-[200] pointer-events-none spin-preview"
      data-testid="spin-preview"
      data-direction={direction}
      style={{ left: x, top: y, transform: 'translate(-50%, -50%)' }}
    >
      <div
        className="spin-preview-ring relative"
        style={{ width: RING, height: RING, '--preview-turn': left ? '-360deg' : '360deg' } as CSSProperties}
      >
        <RoughCircle
          size={RING}
          stroke="var(--accent)"
          strokeWidth={3.4}
          roughness={1.9}
          doubleStroke
          boil={false}
        />
        {HEADS.map((angle) => {
          const radians = (angle * Math.PI) / 180
          return (
            <div
              key={angle}
              className="absolute display text-[34px] leading-none text-[var(--accent)]"
              style={{
                left: RING / 2 + RADIUS * Math.sin(radians),
                top: RING / 2 - RADIUS * Math.cos(radians),
                // ▲ points up, so it has to be turned onto the tangent: a
                // quarter turn past where it sits for a table going clockwise,
                // a quarter turn short of it for one going the other way.
                transform: `translate(-50%, -50%) rotate(${left ? angle - 90 : angle + 90}deg)`,
              }}
            >
              ▲
            </div>
          )
        })}
      </div>
      {/* Above the ring rather than under it. The card being played is held up
          over the middle of the felt, and a line of type down there is a line
          of type behind a card. */}
      <div className="absolute bottom-full left-1/2 mb-2 -translate-x-1/2 display text-[19px] font-bold whitespace-nowrap text-[var(--accent)]">
        everything slides {direction}
      </div>
    </div>
  )
}
