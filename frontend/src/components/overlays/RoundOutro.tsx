import { useEffect, useState } from 'react'
import type { Player } from '../../game/types'
import { signedPoints } from '../../game/types'

interface RoundOutroProps {
  round: number
  /** The round's top scorer, or null when the whole table busted. */
  winner: Player | null
  points: number
  /** Set when the round was cut short by a flip 7. */
  flip7: boolean
  /** What this room plays to — 7, or 9 under "flip 9". */
  flipTarget: number
  /** Epoch millis the card gives way to the scoreboard. */
  untilMs: number
}

/** How long before the handover the card starts clearing itself off. */
const EXIT_MS = 420
/** The dissolve that takes the table away as the card arrives. */
const ENTER_MS = 320

/**
 * The round's closing card. Mirrors the intro: the table is still there as this
 * arrives, so the last hand can be seen, and dissolves away under it — then the
 * card clears and the scoreboard takes the screen. The window is the server's,
 * timed to start after whatever animation ended the round has finished.
 *
 * The backdrop goes fully opaque and stays that way. It used to sit at 75% and
 * then fade itself out over the last beat, which meant the round ended by
 * dissolving back to a live table for 420ms before the scoreboard cut in. Only
 * the writing leaves now; the felt behind it is the same felt the scoreboard is
 * on, so the handover has nothing to show.
 */
export function RoundOutro({ round, winner, points, flip7, flipTarget, untilMs }: RoundOutroProps) {
  const [phase, setPhase] = useState<'in' | 'hold' | 'out'>('in')

  useEffect(() => {
    const settle = window.setTimeout(() => setPhase('hold'), 60)
    const leave = window.setTimeout(
      () => setPhase('out'),
      Math.max(160, untilMs - Date.now() - EXIT_MS),
    )
    return () => {
      window.clearTimeout(settle)
      window.clearTimeout(leave)
    }
  }, [untilMs])

  const held = phase === 'hold'

  return (
    <div
      className="fixed inset-0 z-[240] flex flex-col items-center justify-center pointer-events-none bg-[var(--felt)]"
      data-testid="round-outro"
      data-round={round}
      data-winner={winner?.name ?? ''}
      data-flip7={flip7}
      style={{
        opacity: phase === 'in' ? 0 : 1,
        transition: `opacity ${ENTER_MS}ms ease-out`,
      }}
    >
      <div
        className="text-[var(--ink-soft)] text-lg"
        style={{
          opacity: held ? 1 : 0,
          transition: 'opacity 260ms ease-out',
        }}
      >
        round {String(round).padStart(2, '0')} over
      </div>

      <div
        className="display text-[62px] font-bold leading-none mt-1 text-center"
        style={{
          transform: held ? 'scale(1) rotate(-1deg)' : phase === 'in' ? 'scale(0.75)' : 'scale(1.08)',
          opacity: held ? 1 : 0,
          transition: phase === 'in'
            ? 'transform 380ms cubic-bezier(.2,.9,.3,1.35), opacity 260ms ease-out'
            : 'transform 380ms ease-in, opacity 380ms ease-in',
        }}
      >
        {winner ? (
          <>
            <span className="text-[var(--accent)]">{winner.name}</span> takes it
          </>
        ) : (
          <span className="text-[var(--accent)]">everyone busted!</span>
        )}
      </div>

      {winner && (
        <div
          className="number text-[40px] mt-2"
          style={{
            transform: held ? 'translateY(0)' : 'translateY(12px)',
            opacity: held ? 1 : 0,
            transition: 'transform 380ms cubic-bezier(.2,.9,.3,1.3) 120ms, opacity 300ms ease-out 120ms',
          }}
        >
          {signedPoints(points)}
          {flip7 && <span className="display text-2xl text-[var(--accent)] ml-2">flip {flipTarget}!</span>}
        </div>
      )}
    </div>
  )
}
