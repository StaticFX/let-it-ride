import type { CSSProperties } from 'react'

/** Full turns before the bottle slows onto its victim. */
const TURNS = 3

/**
 * Assassination: a bottle drops onto the middle of the table, spins, and stops
 * pointing at somebody.
 *
 * [bearing] is degrees clockwise from the bottle's resting north to the seat the
 * server picked, worked out from the seat's own coordinates — the spin has to
 * end on a real place on this screen, so the final angle is handed to the
 * keyframe as a variable rather than baked in as a round number of turns.
 * Nothing here decides who it lands on; four clients rolling their own would
 * show four different bottles.
 */
export function SpinningBottle({ bearing, victimName, x, y, ms }: {
  bearing: number
  victimName: string
  x: number
  y: number
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  return (
    <div
      className="fixed z-[215] pointer-events-none"
      data-testid="bottle-spin"
      data-victim={victimName}
      data-bearing={Math.round(bearing)}
      style={{ '--bottle-dur': `${ms}ms`, left: x, top: y } as CSSProperties}
    >
      <div
        className="bottle-spin"
        style={{ '--bottle-end': `${TURNS * 360 + bearing}deg` } as CSSProperties}
      >
        <svg width="36" height="112" viewBox="0 0 36 112" fill="none" aria-hidden="true">
          {/* Neck up: rotating the whole thing about its middle is what aims it. */}
          <path
            d="M13.5 5 q4.5 -2.5 9 0 l0 21 q0 7 4.5 13.5 q4 6.5 4 15.5 l0 45 q0 7.5 -7.5 7.5 l-16 0 q-7.5 0 -7.5 -7.5 l0 -45 q0 -9 4 -15.5 q4.5 -6.5 4.5 -13.5 z"
            fill="var(--card-face)"
            stroke="var(--ink)"
            strokeWidth="2.4"
            strokeLinejoin="round"
          />
          <path d="M13 4.5 q5 -2 10 0" stroke="var(--accent)" strokeWidth="4" strokeLinecap="round" />
          <path d="M8.5 63 l19 0 M8.5 78 l19 0" stroke="var(--ink)" strokeWidth="1.6" opacity="0.35" />
          <path d="M11 46 q7 -4 14 0" stroke="var(--ink)" strokeWidth="1.4" opacity="0.3" />
        </svg>
      </div>

      <div className="bottle-verdict display text-[26px] font-bold text-[var(--accent)] whitespace-nowrap">
        {victimName}!
      </div>
    </div>
  )
}
