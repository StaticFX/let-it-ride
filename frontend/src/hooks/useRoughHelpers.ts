import { useEffect, useState } from 'react'

/**
 * The boil — every drawn line on the table redrawn a few times a second, so the
 * felt looks like somebody is still sketching it.
 *
 * It is the most expensive decoration in the game by a distance: every
 * `RoughShape` on screen re-runs rough.js and re-rasterises its SVG on each
 * tick, and a table mid-round is seventy or eighty of them. On a desktop that
 * is free. It is not free on a phone, where it lands on the same main thread a
 * tap is waiting on, and it is not *visible* on one either — at six inches a
 * pen line wobbling by a pixel is a pen line.
 *
 * So it is decided once, here, rather than at seventy call sites: a coarse
 * pointer, a narrow window or somebody who has asked for less movement gets the
 * table drawn once and left alone. This is also, incidentally, the first time
 * the boil has honoured `prefers-reduced-motion` at all — the twenty-animation
 * block at the bottom of index.css could never reach it, because it is a React
 * re-render rather than a CSS animation.
 */
const BOIL_MS = 260

function boilWanted(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return true
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  if (window.matchMedia('(hover: none)').matches) return false
  return window.innerWidth >= 720
}

const listeners = new Set<(t: number) => void>()
let tick = 0
let timer: ReturnType<typeof setInterval> | null = null

/**
 * Started on the first shape that wants it and stopped after the last one, so a
 * title card with four drawn boxes on it is not waking the device forty times a
 * minute for the whole time somebody leaves the tab open.
 */
function subscribe(fn: (t: number) => void): () => void {
  listeners.add(fn)
  if (timer === null) {
    timer = setInterval(() => {
      tick = (tick + 1) & 0xffff
      listeners.forEach((listener) => listener(tick))
    }, BOIL_MS)
  }
  return () => {
    listeners.delete(fn)
    if (listeners.size === 0 && timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }
}

export function useBoilTick(enabled = true) {
  const [t, setT] = useState(tick)
  useEffect(() => {
    if (!enabled || !boilWanted()) return
    return subscribe(setT)
  }, [enabled])
  return enabled ? t : 0
}

let seedCounter = 1000
export function useSeedOffset() {
  const [offset] = useState(() => (seedCounter++) & 0xffff)
  return offset
}
