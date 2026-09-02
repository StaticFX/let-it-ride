import { useState, useEffect } from 'react'
import { theme } from '../../theme'

const SLOT_SYMBOLS = ['7', '★', '♦', '☠', '✦', '♣', '†', '!', '♠', '♥']
const REEL_ITEM_H = 58

function SlotReel({ spinning, result, stopDelay }: { spinning: boolean; result: string; stopDelay: number }) {
  const [stopped, setStopped] = useState(false)
  const ink = theme.ink

  useEffect(() => {
    if (!spinning) { queueMicrotask(() => setStopped(false)); return }
    const t = setTimeout(() => setStopped(true), stopDelay)
    return () => clearTimeout(t)
  }, [spinning, stopDelay])

  const showResult = !spinning || stopped
  const [strip] = useState(() =>
    Array.from({ length: 30 }, () => SLOT_SYMBOLS[Math.floor(Math.random() * SLOT_SYMBOLS.length)])
  )

  return (
    <div style={{ width: 60, height: REEL_ITEM_H, overflow: 'hidden', background: theme.cardFace, border: `${theme.strokeWidth * 0.7}px solid ${ink}20`, borderRadius: 5, position: 'relative' }}>
      {!showResult && (
        <div style={{ display: 'flex', flexDirection: 'column', '--reel-len': strip.length / 2, animation: `slotReelSpin 0.8s linear infinite` } as React.CSSProperties}>
          {strip.map((s, i) => (
            <div key={i} style={{ height: REEL_ITEM_H, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: theme.fontDisplay, fontSize: 32, fontWeight: 700, color: `${ink}70` }}>{s}</div>
          ))}
        </div>
      )}
      {showResult && (
        <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: theme.fontDisplay, fontSize: 34, fontWeight: 700, color: stopped ? theme.actionAccent : `${ink}25`, animation: stopped ? 'slotLand 350ms cubic-bezier(.2,.9,.3,1.3) both' : 'none' }}>
          {stopped ? result : '?'}
        </div>
      )}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 14, background: `linear-gradient(${theme.cardFace}, transparent)`, pointerEvents: 'none', zIndex: 1 }} />
      <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: 14, background: `linear-gradient(transparent, ${theme.cardFace})`, pointerEvents: 'none', zIndex: 1 }} />
    </div>
  )
}

export function SlotMachine({ onDone }: { onDone: () => void }) {
  const [phase, setPhase] = useState<'enter' | 'pull' | 'spinning' | 'result' | 'exit'>('enter')
  const [results] = useState(() =>
    Array.from({ length: 3 }, () => SLOT_SYMBOLS[Math.floor(Math.random() * SLOT_SYMBOLS.length)])
  )

  useEffect(() => {
    const t0 = setTimeout(() => setPhase('pull'), 500)
    const t1 = setTimeout(() => setPhase('spinning'), 900)
    const t2 = setTimeout(() => setPhase('result'), 3400)
    const t3 = setTimeout(() => setPhase('exit'), 4400)
    const t4 = setTimeout(onDone, 4800)
    return () => { clearTimeout(t0); clearTimeout(t1); clearTimeout(t2); clearTimeout(t3); clearTimeout(t4) }
  }, [onDone])

  const ink = theme.ink
  const sw = theme.strokeWidth
  const isSpinning = phase === 'spinning'

  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 350, pointerEvents: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(246, 241, 227, 0.55)', opacity: phase === 'exit' ? 0 : 1, transition: 'opacity 400ms ease' }} />
      <div style={{
        position: 'relative', zIndex: 1,
        animation: phase === 'enter' || phase === 'pull' ? 'slotMachineEntry 450ms cubic-bezier(.2,.9,.3,1.3) both' : phase === 'exit' ? 'slotMachineExit 400ms ease-in both' : 'none',
      }}>
        <div style={{ background: theme.cardFace, border: `${sw * 1.5}px solid ${ink}`, borderRadius: 10, boxShadow: `5px 5px 0 0 ${ink}`, padding: '20px 24px 16px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, minWidth: 240 }}>
          <div style={{ fontFamily: theme.fontDisplay, fontSize: 30, fontWeight: 700, color: theme.actionAccent, transform: 'rotate(-1.5deg)', animation: 'swayMore 2.2s ease-in-out infinite', letterSpacing: '0.05em' }}>~ slots ~</div>
          <div style={{ display: 'flex', gap: 6, background: `${ink}06`, border: `${sw}px solid ${ink}18`, borderRadius: 8, padding: '10px 12px', boxShadow: `inset 0 2px 8px ${ink}08` }}>
            {[0, 1, 2].map((i) => (
              <SlotReel key={i} spinning={isSpinning} result={results[i]} stopDelay={1200 + i * 500} />
            ))}
          </div>
          <div style={{ fontFamily: theme.fontBody, fontSize: 15, color: theme.inkSoft, minHeight: 20 }}>
            {phase === 'pull' && 'pulling lever...'}
            {phase === 'spinning' && 'spinning...'}
            {(phase === 'result' || phase === 'exit') && 'drawing your card!'}
          </div>
        </div>
        <div style={{ position: 'absolute', right: -22, top: '35%', width: 16, height: 50, transformOrigin: 'top center', animation: phase === 'pull' ? 'slotHandlePull 400ms ease-out' : 'none' }}>
          <div style={{ width: 4, height: 38, background: `${ink}50`, borderRadius: 2, margin: '0 auto' }} />
          <div style={{ width: 16, height: 16, borderRadius: '50%', background: theme.actionAccent, border: `${sw}px solid ${ink}`, margin: '-2px auto 0' }} />
        </div>
      </div>
    </div>
  )
}
