import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { RoughCircle } from '../ui/RoughShapes'

/** The face the coin shows unturned; the other one is half a turn away. */
const HEADS = 'heads'

/** Full turns before the coin settles. Enough to lose count of the faces. */
const TURNS = 5

const COIN_SIZE = 104

/**
 * How far into the animation the coin is down — the landing frame of `coinToss`
 * in index.css, as a fraction, so the caption and the keyframes cannot drift
 * apart when the animation is given more or less time.
 */
const LAND_AT = 0.7

function CoinFace({ label, back }: { label: string; back?: boolean }) {
  return (
    <div className={`coin-face ${back ? 'coin-face-back' : ''}`}>
      <RoughCircle
        size={COIN_SIZE}
        stroke="var(--ink)"
        strokeWidth={2.4}
        roughness={1.9}
        fill="var(--card-face)"
        doubleStroke
        boil={false}
      />
      <span className="display text-[26px] font-bold text-[var(--accent)] relative">{label}</span>
    </div>
  )
}

/**
 * Coin flip: the coin drops onto the table, turns over and over, and lands on
 * the face the server already announced.
 *
 * Both the call and the result travel on the event precisely so the coin can
 * land on the right side — rolling a face here would leave four clients showing
 * four different coins, and the bust or the ×2 that follows in the same batch
 * would contradict three of them.
 */
export function CoinToss({ call, result, x, y, ms }: {
  call: string
  result: string
  x: number
  y: number
  /** The whole animation's budget — see `GameAnimation.ms`. */
  ms: number
}) {
  const [landed, setLanded] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => setLanded(true), ms * LAND_AT)
    return () => window.clearTimeout(timer)
  }, [ms])

  const won = call === result
  // Heads sits at 0 and tails half a turn behind it, so which face is up at the
  // end is only a matter of how many half-turns the coin makes on the way down.
  const endDeg = TURNS * 360 + (result === HEADS ? 0 : 180)

  return (
    <div
      className="coin-flip fixed z-[215] pointer-events-none flex flex-col items-center gap-3"
      data-testid="coin-flip"
      data-call={call}
      data-result={result}
      data-landed={landed}
      style={{ '--coin-dur': `${ms}ms`, left: x, top: y, transform: 'translate(-50%, -50%)' } as CSSProperties}
    >
      <div
        className="coin-toss"
        style={{
          '--coin-end': `${endDeg}deg`,
          '--coin-dur': `${ms}ms`,
          width: COIN_SIZE,
          height: COIN_SIZE,
        } as CSSProperties}
      >
        <CoinFace label={HEADS} />
        <CoinFace label="tails" back />
      </div>

      {/* Keyed on which of the two things it is saying, so the verdict lands
          with its own beat rather than the call quietly becoming it. */}
      <div
        key={landed ? 'landed' : 'calling'}
        className={`display text-[24px] font-bold whitespace-nowrap coin-call ${
          landed ? (won ? 'text-[var(--passive)]' : 'text-[var(--accent)]') : 'text-[var(--ink-soft)]'
        }`}
      >
        {landed ? `${result}! ${won ? 'called it' : `you said ${call}`}` : `calling ${call}…`}
      </div>
    </div>
  )
}
