import type { TurnTimer } from '../../hooks/useGame'

const SIZE = 44
const RADIUS = 18
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** The turn clock. Runs out → the backend sends the player out automatically. */
export function TurnClock({ timer, label }: { timer: TurnTimer; label?: string }) {
  const seconds = Math.ceil(timer.remainingMs / 1000)
  const urgent = timer.remainingMs <= 5000

  return (
    <div className="flex items-center gap-2">
      <div className="relative" style={{ width: SIZE, height: SIZE }}>
        <svg width={SIZE} height={SIZE} className="-rotate-90">
          <circle
            cx={SIZE / 2}
            cy={SIZE / 2}
            r={RADIUS}
            fill="none"
            stroke="var(--ink)"
            strokeOpacity={0.15}
            strokeWidth={3}
          />
          <circle
            cx={SIZE / 2}
            cy={SIZE / 2}
            r={RADIUS}
            fill="none"
            stroke={urgent ? 'var(--accent)' : 'var(--ink)'}
            strokeWidth={3}
            strokeLinecap="round"
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={CIRCUMFERENCE * (1 - timer.fraction)}
            className="transition-[stroke-dashoffset] duration-100 ease-linear"
          />
        </svg>
        <div
          className={`absolute inset-0 flex items-center justify-center number text-lg leading-none ${
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
