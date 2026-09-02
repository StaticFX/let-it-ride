import { useEffect, useState } from 'react'
import type { Player } from '../../game/types'

interface RoundOutroProps {
  round: number
  /** The round's top scorer, or null when the whole table busted. */
  winner: Player | null
  points: number
  /** Set when the round was cut short by a flip 7. */
  flip7: boolean
  /** Epoch millis the card gives way to the scoreboard. */
  untilMs: number
}

const FADE_OUT_MS = 420

/**
 * The round's closing card. Mirrors the intro: the table stays visible behind
 * it for a beat so the last hand can be seen, then this hands over to the
 * scoreboard. The window is the server's, timed to start after whatever
 * animation ended the round has finished.
 */
export function RoundOutro({ round, winner, points, flip7, untilMs }: RoundOutroProps) {
  const [phase, setPhase] = useState<'in' | 'hold' | 'out'>('in')

  useEffect(() => {
    const settle = window.setTimeout(() => setPhase('hold'), 60)
    const leave = window.setTimeout(
      () => setPhase('out'),
      Math.max(160, untilMs - Date.now() - FADE_OUT_MS),
    )
    return () => {
      window.clearTimeout(settle)
      window.clearTimeout(leave)
    }
  }, [untilMs])

  const held = phase === 'hold'

  return (
    <div
      className="fixed inset-0 z-[240] flex flex-col items-center justify-center pointer-events-none backdrop-blur-[10px] bg-[var(--felt)]/75"
      style={{
        opacity: phase === 'out' ? 0 : 1,
        transition: phase === 'in' ? 'opacity 260ms ease-out' : `opacity ${FADE_OUT_MS}ms ease-in`,
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
          +{points}
          {flip7 && <span className="display text-2xl text-[var(--accent)] ml-2">flip 7!</span>}
        </div>
      )}
    </div>
  )
}
