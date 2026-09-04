import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { RoughCircle } from '../ui/RoughShapes'

/** The face the coin shows unturned; the other one is half a turn away. */
const HEADS = 'heads'

/** Full turns before the coin settles. Enough to lose count of the faces. */
const TURNS = 5

const COIN_SIZE = 104

/**
 * When the coin has settled — keep in step with the landing frame of `coinToss`
 * in index.css. Only the caption reads it; the coin itself is pure CSS.
 */
const LAND_MS = 1400

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
export function CoinToss({ call, result, x, y }: { call: string; result: string; x: number; y: number }) {
  const [landed, setLanded] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => setLanded(true), LAND_MS)
    return () => window.clearTimeout(timer)
  }, [])

  const won = call === result
  // Heads sits at 0 and tails half a turn behind it, so which face is up at the
  // end is only a matter of how many half-turns the coin makes on the way down.
  const endDeg = TURNS * 360 + (result === HEADS ? 0 : 180)

  return (
    <div
      className="fixed z-[215] pointer-events-none flex flex-col items-center gap-3"
      data-testid="coin-flip"
      data-call={call}
      data-result={result}
      data-landed={landed}
      style={{ left: x, top: y, transform: 'translate(-50%, -50%)' }}
    >
      <div
        className="coin-toss"
        style={{ '--coin-end': `${endDeg}deg`, width: COIN_SIZE, height: COIN_SIZE } as CSSProperties}
      >
        <CoinFace label={HEADS} />
        <CoinFace label="tails" back />
      </div>

      <div
        className={`display text-[24px] font-bold whitespace-nowrap coin-call ${
          landed ? (won ? 'text-[var(--passive)]' : 'text-[var(--accent)]') : 'text-[var(--ink-soft)]'
        }`}
      >
        {landed ? `${result}! ${won ? 'called it' : `you said ${call}`}` : `calling ${call}…`}
      </div>
    </div>
  )
}
