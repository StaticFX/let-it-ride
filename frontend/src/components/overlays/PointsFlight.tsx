import { useLayoutEffect, useRef } from 'react'
import { RoughCircle } from '../ui/RoughShapes'

/**
 * Points crossing the table.
 *
 * Every other way a score moves in this game happens on the summary screen,
 * where a number quietly reads differently than it did before. A toll charged
 * in the middle of a round has to be watchable or it may as well not have
 * happened — so the points are a thing: a chip lifted off the seat that paid,
 * carried over the felt, and dropped on the seat that collected.
 *
 * The chip is on its way before it is read. What is left behind at each end is
 * the sum, in the two colours the rest of the table already uses for a round
 * that gained and a round that cost.
 */
export function PointsFlight({ points, from, to, ms }: {
  points: number
  from: { x: number; y: number }
  to: { x: number; y: number }
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    // Two frames: the first paints it at the payer's seat, the second gives it
    // somewhere to go. Setting both in one pass is a chip that was always at
    // the far end.
    const frame = requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        element.style.left = `${to.x}px`
        element.style.top = `${to.y}px`
        element.style.transform = 'translate(-50%, -50%) scale(0.9) rotate(18deg)'
      }),
    )
    return () => cancelAnimationFrame(frame)
  }, [to.x, to.y])

  return (
    <>
      <div
        data-testid="points-paid"
        className="fixed z-[214] pointer-events-none display text-[22px] font-bold text-[var(--accent)] points-toll whitespace-nowrap"
        style={{ left: from.x, top: from.y - 88 }}
      >
        − {points}
      </div>

      <div
        ref={ref}
        data-testid="points-flight"
        data-points={points}
        className="fixed z-[216] pointer-events-none"
        style={{
          left: from.x,
          top: from.y,
          transform: 'translate(-50%, -50%) scale(1.15) rotate(-12deg)',
          transition: `left ${ms * 0.7}ms cubic-bezier(.35,.85,.35,1.05), top ${ms * 0.7}ms cubic-bezier(.35,.85,.35,1.05), transform ${ms * 0.7}ms cubic-bezier(.35,.85,.35,1.05)`,
        }}
      >
        <div className="relative flex h-[58px] w-[58px] items-center justify-center">
          <RoughCircle
            size={58}
            stroke="var(--ink)"
            strokeWidth={2.2}
            roughness={2.1}
            fill="var(--card-face)"
            doubleStroke
            boil={false}
          />
          <span className="number relative text-[24px] leading-none">{points}</span>
        </div>
      </div>

      <div
        data-testid="points-taken"
        className="fixed z-[214] pointer-events-none display text-[22px] font-bold text-[var(--passive)] points-take whitespace-nowrap"
        style={{ left: to.x, top: to.y - 88, animationDelay: `${Math.round(ms * 0.55)}ms` }}
      >
        + {points}
      </div>
    </>
  )
}
