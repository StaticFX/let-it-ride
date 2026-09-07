import { useEffect, useState } from 'react'

/**
 * Seconds left on one of the server's deadlines, or null when there is none.
 *
 * Every deadline in this game is absolute and the server's alone — the next
 * round dealing itself, the shop shutting — and this only counts one down for
 * display. A slow tab shows a stale number rather than acting at the wrong
 * time, which is the whole reason the server keeps the clock and the client
 * only reads it. Ticking every 250ms keeps the visible second honest without
 * waiting up to a full one to notice the first change.
 */
export function useCountdown(deadline?: number): number | null {
  const [clock, setClock] = useState(() => Date.now())

  useEffect(() => {
    if (!deadline) return
    const interval = window.setInterval(() => setClock(Date.now()), 250)
    return () => window.clearInterval(interval)
  }, [deadline])

  if (!deadline) return null
  return Math.max(0, Math.ceil((deadline - clock) / 1000))
}
