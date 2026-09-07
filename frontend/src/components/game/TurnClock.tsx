import type { TurnTimer } from '../../hooks/useGame'

const SIZES = {
  sm: { box: 44, radius: 18, stroke: 3, digits: 'text-lg' },
  lg: { box: 86, radius: 36, stroke: 5, digits: 'text-[40px]' },
}

/** The turn clock. Runs out → the backend sends the player out automatically. */
export function TurnClock({ timer, label, size = 'sm', testId = 'turn-clock' }: {
  timer: TurnTimer
  label?: string
  size?: keyof typeof SIZES
  /**
   * Stable handle for the end-to-end suite. It has an id because the turn clock
   * is the only clock on the table today; a second one — a window between rounds,
   * say — has to be tellable from it, and a spec that found two of the same id
   * would silently read whichever came first in the document.
   */
  testId?: string
}) {
  const seconds = Math.ceil(timer.remainingMs / 1000)
  const urgent = timer.remainingMs <= 5000
  const { box, radius, stroke, digits } = SIZES[size]
  const circumference = 2 * Math.PI * radius

  return (
    <div className="flex items-center gap-2" data-testid={testId} data-seconds={seconds} data-urgent={urgent}>
      <div className="relative" style={{ width: box, height: box }}>
        <svg width={box} height={box} className="-rotate-90">
          <circle
            cx={box / 2}
            cy={box / 2}
            r={radius}
            fill="none"
            stroke="var(--ink)"
            strokeOpacity={0.15}
            strokeWidth={stroke}
          />
          <circle
            cx={box / 2}
            cy={box / 2}
            r={radius}
            fill="none"
            stroke={urgent ? 'var(--accent)' : 'var(--ink)'}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={circumference * (1 - timer.fraction)}
            className="transition-[stroke-dashoffset] duration-100 ease-linear"
          />
        </svg>
        <div
          className={`absolute inset-0 flex items-center justify-center number ${digits} leading-none ${
            urgent ? 'text-[var(--accent)] animate-[swayMore_0.6s_ease-in-out_infinite]' : ''
          }`}
        >
          {seconds}
        </div>
      </div>
      {label && <small className="whitespace-nowrap">{label}</small>}
    </div>
  )
}
