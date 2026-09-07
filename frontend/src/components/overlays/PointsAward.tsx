import { useLayoutEffect, useRef } from 'react'
import { signedPoints } from '../../game/types'

/**
 * A round's points going home.
 *
 * Written in the same hand as everything else on the felt — a number torn off
 * the pad rather than a chip, because this is a score being written down rather
 * than money changing hands. It lifts off the seat that made it and lands on
 * that player's line on the scoreboard, which is where the total moves.
 *
 * A round worth nothing still flies: a seat that busted watching its zero go
 * across the table is the point of paying the table one at a time.
 */
export function PointsAward({ points, playerId, from, measure, ms }: {
  points: number
  playerId: string
  from: { x: number; y: number }
  /**
   * Where this player's line on the scoreboard is, measured when the flight
   * starts rather than handed in. Reading the page is not a render's business,
   * and the line is at the far end of a layout that has to have happened
   * first — so it is asked for here, inside the effect that sets off.
   */
  measure: (playerId: string) => DOMRect | null
  /** How long it has to get there. */
  ms: number
}) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const row = measure(playerId)
    // No line to land on — a scoreboard that has not laid out yet — and the
    // points simply rise off the seat rather than fly off to nowhere.
    const to = row
      ? { x: row.right - 26, y: row.top + row.height / 2 }
      : { x: from.x, y: from.y - 90 }
    // Two frames: the first paints it over the seat, the second gives it
    // somewhere to be. Both in one pass is a number that was always there.
    const frame = requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        element.style.left = `${to.x}px`
        element.style.top = `${to.y}px`
        element.style.transform = 'translate(-50%, -50%) scale(0.72) rotate(-4deg)'
        element.style.opacity = '0'
      }),
    )
    return () => cancelAnimationFrame(frame)
  }, [measure, playerId, from.x, from.y])

  const lost = points < 0

  return (
    <div
      ref={ref}
      data-testid="points-award"
      data-player-points={points}
      className={`fixed z-[216] pointer-events-none number text-[34px] font-bold whitespace-nowrap ${
        lost ? 'text-[var(--accent)]' : 'text-[var(--ink)]'
      }`}
      style={{
        left: from.x,
        top: from.y,
        transform: 'translate(-50%, -50%) scale(1.15) rotate(3deg)',
        transition: `left ${ms}ms cubic-bezier(.4,.05,.35,1), top ${ms}ms cubic-bezier(.4,.05,.35,1), transform ${ms}ms cubic-bezier(.4,.05,.35,1), opacity ${ms}ms ease-in`,
      }}
    >
      {signedPoints(points)}
    </div>
  )
}
