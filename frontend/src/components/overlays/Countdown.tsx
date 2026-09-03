import { useState, useEffect } from 'react'

export function Countdown({ onDone }: { onDone: () => void }) {
  const [count, setCount] = useState(5)

  useEffect(() => {
    if (count <= 0) { onDone(); return }
    const t = setTimeout(() => setCount(count - 1), 1000)
    return () => clearTimeout(t)
  }, [count, onDone])

  return (
    <div className="fixed inset-0 z-[400] bg-[var(--felt)]/85 flex items-center justify-center" data-testid="countdown" data-count={count}>
      <div className="text-center">
        <div className={`display text-[120px] leading-none transition-colors duration-300 animate-[swayMore_0.8s_ease-in-out_infinite] ${count <= 2 ? 'text-[var(--accent)]' : ''}`}>
          {count || 'GO!'}
        </div>
        <p className="text-muted text-lg mt-3">get ready...</p>
      </div>
    </div>
  )
}
