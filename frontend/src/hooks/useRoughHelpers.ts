import { useEffect, useState } from 'react'

// --- Global boil tick ---
const BOIL_MS = 260
const listeners = new Set<(t: number) => void>()
let tick = 0
setInterval(() => {
  tick = (tick + 1) & 0xffff
  listeners.forEach(fn => fn(tick))
}, BOIL_MS)

export function useBoilTick(enabled = true) {
  const [t, setT] = useState(tick)
  useEffect(() => {
    if (!enabled) return
    const fn = (n: number) => setT(n)
    listeners.add(fn)
    return () => { listeners.delete(fn) }
  }, [enabled])
  return enabled ? t : 0
}

let seedCounter = 1000
export function useSeedOffset() {
  const [offset] = useState(() => (seedCounter++) & 0xffff)
  return offset
}
