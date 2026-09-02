import { useEffect, useState } from 'react'
import type { Card } from '../../game/types'
import { findAction, findPassive, useCatalog } from '../../state/gameStore'
import { PlayingCard } from '../cards/PlayingCard'

const SPIN_SYMBOLS = ['7', '★', '♦', '☠', '✦', '♣', '†', '!', '♠', '♥']
const REEL_HEIGHT = 58
const REEL_STOP_STAGGER_MS = 280
const SHOW_CARD_MS = 1300

/**
 * The reels spin until the server tells us what was actually drawn, then land
 * on it. The old version span for a fixed 3.4s onto three random symbols that
 * had nothing to do with the card, and finished at whatever moment it felt
 * like — so it routinely showed a result before or after the real one arrived.
 */
function reelSymbol(card: Card, catalog: ReturnType<typeof useCatalog>): string {
  if (card.kind === 'number') return card.label
  const def = findAction(catalog, card.defId) ?? findPassive(catalog, card.defId)
  return def?.sigil ?? '?'
}

function Reel({ symbol, stopped, delayMs }: { symbol: string; stopped: boolean; delayMs: number }) {
  const [landed, setLanded] = useState(false)
  const [strip] = useState(() =>
    Array.from({ length: 30 }, () => SPIN_SYMBOLS[Math.floor(Math.random() * SPIN_SYMBOLS.length)]),
  )

  useEffect(() => {
    if (!stopped) return
    const timer = window.setTimeout(() => setLanded(true), delayMs)
    return () => window.clearTimeout(timer)
  }, [stopped, delayMs])

  return (
    <div
      className="relative overflow-hidden rounded-[5px] bg-[var(--card-face)] border-[1.4px] border-[var(--ink)]/20"
      style={{ width: 60, height: REEL_HEIGHT }}
    >
      {landed ? (
        <div
          className="h-full flex items-center justify-center display text-[34px] font-bold text-[var(--accent)]"
          style={{ animation: 'slotLand 350ms cubic-bezier(.2,.9,.3,1.3) both' }}
        >
          {symbol}
        </div>
      ) : (
        <div
          className="flex flex-col"
          style={{ ['--reel-len' as string]: strip.length / 2, animation: 'slotReelSpin 0.8s linear infinite' }}
        >
          {strip.map((s, i) => (
            <div
              key={i}
              className="flex items-center justify-center display text-[32px] font-bold text-[var(--ink)]/45"
              style={{ height: REEL_HEIGHT }}
            >
              {s}
            </div>
          ))}
        </div>
      )}
      <div className="absolute top-0 inset-x-0 h-3.5 bg-gradient-to-b from-[var(--card-face)] to-transparent pointer-events-none z-[1]" />
      <div className="absolute bottom-0 inset-x-0 h-3.5 bg-gradient-to-t from-[var(--card-face)] to-transparent pointer-events-none z-[1]" />
    </div>
  )
}

export function SlotMachine({ card, onDone }: { card: Card | null; onDone: () => void }) {
  const catalog = useCatalog()
  const [pulled, setPulled] = useState(false)
  const [leaving, setLeaving] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => setPulled(true), 400)
    return () => window.clearTimeout(timer)
  }, [])

  // Once the card is known, let the reels land, hold it up, then get out.
  useEffect(() => {
    if (!card) return
    const settle = 2 * REEL_STOP_STAGGER_MS + 400
    const exit = window.setTimeout(() => setLeaving(true), settle + SHOW_CARD_MS)
    const done = window.setTimeout(onDone, settle + SHOW_CARD_MS + 400)
    return () => {
      window.clearTimeout(exit)
      window.clearTimeout(done)
    }
  }, [card, onDone])

  const symbol = card ? reelSymbol(card, catalog) : '?'
  const revealed = !!card

  return (
    <div className="fixed inset-0 z-[350] pointer-events-none flex items-center justify-center">
      <div
        className="absolute inset-0 bg-[var(--felt)]/55 transition-opacity duration-300"
        style={{ opacity: leaving ? 0 : 1 }}
      />

      <div
        className="relative z-[1]"
        style={{
          animation: leaving
            ? 'slotMachineExit 400ms ease-in both'
            : 'slotMachineEntry 450ms cubic-bezier(.2,.9,.3,1.3) both',
        }}
      >
        <div className="bg-[var(--card-face)] border-[3px] border-[var(--ink)] rounded-[10px] px-6 pt-5 pb-4 flex flex-col items-center gap-3 min-w-[240px] shadow-[5px_5px_0_0_var(--ink)]">
          <div className="display text-3xl font-bold text-[var(--accent)] -rotate-[1.5deg] sway-mid tracking-wide">
            ~ slots ~
          </div>

          <div className="flex gap-1.5 bg-[var(--ink)]/5 border-2 border-[var(--ink)]/15 rounded-lg px-3 py-2.5">
            {[0, 1, 2].map((i) => (
              <Reel key={i} symbol={symbol} stopped={revealed} delayMs={i * REEL_STOP_STAGGER_MS} />
            ))}
          </div>

          {/* The card that actually came out of the deck. */}
          <div className="h-[92px] flex items-center justify-center">
            {revealed ? (
              <div style={{ animation: 'slotPayout 500ms 840ms cubic-bezier(.2,.9,.3,1.3) both' }}>
                <PlayingCard card={card} size="small" />
              </div>
            ) : (
              <div className="display text-[15px] text-[var(--ink-soft)]">
                {pulled ? 'spinning…' : 'pulling lever…'}
              </div>
            )}
          </div>
        </div>

        {/* Lever */}
        <div
          className="absolute -right-6 top-[32%] w-4 h-[50px] origin-top"
          style={{ animation: pulled ? 'slotHandlePull 400ms ease-out' : 'none' }}
        >
          <div className="w-1 h-[38px] bg-[var(--ink)]/50 rounded-sm mx-auto" />
          <div className="w-4 h-4 rounded-full bg-[var(--accent)] border-2 border-[var(--ink)] mx-auto -mt-0.5" />
        </div>
      </div>
    </div>
  )
}
