import { useState } from 'react'
import { theme } from '../../theme'

export function ImpactParticles({ x, y }: { x: number; y: number }) {
  const [particles] = useState(() =>
    Array.from({ length: 14 }, (_, i) => {
      const angle = (i / 14) * Math.PI * 2 + (Math.random() - 0.5) * 0.8
      return {
        px: Math.cos(angle) * (50 + Math.random() * 70),
        py: Math.sin(angle) * (50 + Math.random() * 70),
        size: 3 + Math.random() * 5,
        delay: Math.random() * 80,
        color: Math.random() > 0.4 ? theme.actionAccent : theme.ink,
        round: Math.random() > 0.5,
        rot: Math.random() * 360,
      }
    })
  )

  return (
    <div style={{ position: 'fixed', left: x, top: y, zIndex: 250, pointerEvents: 'none' }}>
      {particles.map((p, i) => (
        <div key={i} style={{
          position: 'absolute',
          width: p.size, height: p.size,
          background: p.color,
          borderRadius: p.round ? '50%' : '2px',
          transform: `rotate(${p.rot}deg)`,
          '--px': `${p.px}px`,
          '--py': `${p.py}px`,
          animation: `particleFly 450ms ${p.delay}ms cubic-bezier(.15,.8,.3,1) forwards`,
        } as React.CSSProperties} />
      ))}
    </div>
  )
}
