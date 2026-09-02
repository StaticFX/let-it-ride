import { useEffect, useState } from 'react'
import { theme } from '../../theme'

interface RoundIntroProps {
  round: number
  startingPlayerName: string
  onDone: () => void
}

export function RoundIntro({ round, startingPlayerName, onDone }: RoundIntroProps) {
  const [phase, setPhase] = useState<'in' | 'hold' | 'out'>('in')

  useEffect(() => {
    const t1 = setTimeout(() => setPhase('hold'), 100)
    const t2 = setTimeout(() => setPhase('out'), 1800)
    const t3 = setTimeout(onDone, 2400)
    return () => { clearTimeout(t1); clearTimeout(t2); clearTimeout(t3) }
  }, [onDone])

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 200,
      display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center',
      backdropFilter: 'blur(12px)',
      WebkitBackdropFilter: 'blur(12px)',
      background: `${theme.feltOuter}cc`,
      opacity: phase === 'out' ? 0 : 1,
      transition: phase === 'in'
        ? 'opacity 300ms ease-out'
        : 'opacity 500ms ease-in',
      pointerEvents: 'none',
    }}>
      <div style={{
        fontFamily: theme.fontDisplay,
        fontSize: 80,
        fontWeight: 700,
        color: theme.ink,
        lineHeight: 1,
        transform: phase === 'hold'
          ? 'scale(1) translateY(0)'
          : phase === 'in'
            ? 'scale(0.8) translateY(20px)'
            : 'scale(1.1) translateY(-10px)',
        opacity: phase === 'hold' ? 1 : 0,
        transition: phase === 'in'
          ? 'transform 400ms cubic-bezier(.2,.9,.3,1.3), opacity 300ms ease-out'
          : 'transform 400ms ease-in, opacity 400ms ease-in',
      }}>
        round {round}
      </div>
      <div style={{
        fontFamily: theme.fontBody,
        fontSize: 22,
        color: theme.inkSoft,
        marginTop: 12,
        transform: phase === 'hold' ? 'translateY(0)' : 'translateY(10px)',
        opacity: phase === 'hold' ? 1 : 0,
        transition: phase === 'in'
          ? 'transform 400ms cubic-bezier(.2,.9,.3,1.3) 150ms, opacity 300ms ease-out 150ms'
          : 'transform 300ms ease-in, opacity 300ms ease-in',
      }}>
        {startingPlayerName} starts
      </div>
    </div>
  )
}
