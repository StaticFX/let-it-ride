import { useEffect, useState } from 'react'

interface RoundIntroProps {
  round: number
  startingPlayerName: string
  /** Epoch millis the card must be gone by — the server deals nothing until then. */
  untilMs: number
}

const FADE_OUT_MS = 500

/**
 * The round's title card. Its window is set by the server, which refuses to
 * deal anything while it is up, so the card can never be talking over cards
 * being dealt behind it.
 */
export function RoundIntro({ round, startingPlayerName, untilMs }: RoundIntroProps) {
  const [phase, setPhase] = useState<'in' | 'hold' | 'out'>('in')

  useEffect(() => {
    const settle = window.setTimeout(() => setPhase('hold'), 80)
    const leave = window.setTimeout(
      () => setPhase('out'),
      Math.max(200, untilMs - Date.now() - FADE_OUT_MS),
    )
    return () => {
      window.clearTimeout(settle)
      window.clearTimeout(leave)
    }
  }, [untilMs])

  return (
    <div
      className="fixed inset-0 z-[200] flex flex-col items-center justify-center pointer-events-none backdrop-blur-[12px] bg-[var(--felt)]/80"
      data-testid="round-intro"
      data-round={round}
      style={{
        opacity: phase === 'out' ? 0 : 1,
        transition: phase === 'in' ? 'opacity 300ms ease-out' : `opacity ${FADE_OUT_MS}ms ease-in`,
      }}
    >
      <div
        className="display text-[80px] font-bold leading-none"
        style={{
          transform: phase === 'hold'
            ? 'scale(1) translateY(0)'
            : phase === 'in'
              ? 'scale(0.8) translateY(20px)'
              : 'scale(1.1) translateY(-10px)',
          opacity: phase === 'hold' ? 1 : 0,
          transition: phase === 'in'
            ? 'transform 400ms cubic-bezier(.2,.9,.3,1.3), opacity 300ms ease-out'
            : 'transform 400ms ease-in, opacity 400ms ease-in',
        }}
      >
        round {round}
      </div>
      <div
        className="text-[22px] text-[var(--ink-soft)] mt-3"
        style={{
          transform: phase === 'hold' ? 'translateY(0)' : 'translateY(10px)',
          opacity: phase === 'hold' ? 1 : 0,
          transition: phase === 'in'
            ? 'transform 400ms cubic-bezier(.2,.9,.3,1.3) 150ms, opacity 300ms ease-out 150ms'
            : 'transform 300ms ease-in, opacity 300ms ease-in',
        }}
      >
        {startingPlayerName} starts
      </div>
    </div>
  )
}
