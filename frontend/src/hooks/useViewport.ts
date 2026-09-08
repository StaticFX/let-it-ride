import { useEffect, useState } from 'react'

/**
 * How wide a window has to be before the felt is drawn the way it always was.
 *
 * Not a device test and deliberately not a phone test — a laptop window dragged
 * down to a third of the screen has exactly the same problem a phone has, and
 * gets exactly the same answer. Everything downstream of this reads `compact`
 * and never asks what it is running on.
 */
export const COMPACT_WIDTH = 720

/**
 * ...and how short, which is the same question asked the other way round.
 *
 * A phone turned on its side is 844 wide and 390 tall. It is not narrow, and by
 * width alone it would be handed the wide felt — whose arc is 335% of nothing
 * and whose own hand wants 180px of a 390px screen. Whether an arc fits is a
 * question about both edges of the window, so both are asked.
 */
export const COMPACT_HEIGHT = 560

/** How far in from each edge of the window it is actually safe to draw. */
export interface Insets {
  top: number
  right: number
  bottom: number
  left: number
}

const NO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 }

/**
 * Reads the safe-area insets back out of the stylesheet.
 *
 * They are declared once in `index.css` as `env(safe-area-inset-*)` and read
 * here rather than being asked for a second time, so the felt's geometry and
 * the padding on every corner of it cannot disagree about where the notch is.
 * A browser that does not resolve them hands back an empty string, which is the
 * same answer as a browser with no cutouts — zero.
 */
function readInsets(): Insets {
  if (typeof window === 'undefined') return NO_INSETS
  const style = getComputedStyle(document.documentElement)
  const read = (name: string) => {
    const value = parseFloat(style.getPropertyValue(name))
    return Number.isFinite(value) ? value : 0
  }
  return {
    top: read('--safe-top'),
    right: read('--safe-right'),
    bottom: read('--safe-bottom'),
    left: read('--safe-left'),
  }
}

function sameInsets(a: Insets, b: Insets): boolean {
  return a.top === b.top && a.right === b.right && a.bottom === b.bottom && a.left === b.left
}

/** Whether this browser is being pointed at rather than hovered over. */
function touchOnly(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false
  // `hover: none` is the question actually worth asking. A laptop with a
  // touchscreen has both, and taking the hover away from it because it *could*
  // be tapped would be a downgrade for a machine that was fine.
  return window.matchMedia('(hover: none)').matches
}

export interface Viewport {
  w: number
  h: number
  /** Too small for the wide felt — see [COMPACT_WIDTH] and [COMPACT_HEIGHT]. */
  compact: boolean
  /** Taller than it is wide, which is the case the felt has to work hardest for. */
  portrait: boolean
  /** No hover to hang anything on, so every affordance has to survive a tap. */
  touch: boolean
  /** What the notch and the home bar are covering — see [readInsets]. */
  insets: Insets
}

/**
 * The size of the window the game is being drawn in, and the two facts about it
 * the table lays itself out from.
 *
 * `window.innerWidth/Height` rather than `visualViewport`, on purpose: the shell
 * is `100dvh` and the geometry below has to agree with what CSS actually
 * painted. The visual viewport shrinks under a soft keyboard and while an iOS
 * URL bar is halfway through collapsing, and a table that re-laid itself out on
 * every frame of that would be reading a size nothing on screen has. The
 * `visualViewport` listener is still here because it is the only event iOS
 * reliably fires when the bar finishes moving — what is *read* on that event is
 * still the layout viewport.
 */
export function useViewport(): Viewport {
  const [size, setSize] = useState(() => ({ w: window.innerWidth, h: window.innerHeight }))
  const [touch, setTouch] = useState(touchOnly)
  const [insets, setInsets] = useState(NO_INSETS)

  useEffect(() => {
    const onResize = () => {
      setSize((prev) => {
        const w = window.innerWidth
        const h = window.innerHeight
        // Orientation change fires this twice on some phones, once with the old
        // numbers. Bailing on an unchanged pair keeps that from re-rendering
        // every card on the table for nothing.
        if (prev.w === w && prev.h === h) return prev
        return { w, h }
      })
      // The notch swaps sides when a phone is turned over, so this is a fact
      // about the current orientation and not about the device.
      setInsets((prev) => {
        const next = readInsets()
        return sameInsets(prev, next) ? prev : next
      })
    }

    window.addEventListener('resize', onResize)
    window.addEventListener('orientationchange', onResize)
    window.visualViewport?.addEventListener('resize', onResize)

    const hover = window.matchMedia?.('(hover: none)')
    const onHoverChange = () => setTouch(touchOnly())
    hover?.addEventListener('change', onHoverChange)

    // A phone that was rotated while the tab was in the background comes back
    // with a size nothing told us about.
    onResize()

    return () => {
      window.removeEventListener('resize', onResize)
      window.removeEventListener('orientationchange', onResize)
      window.visualViewport?.removeEventListener('resize', onResize)
      hover?.removeEventListener('change', onHoverChange)
    }
  }, [])

  return {
    w: size.w,
    h: size.h,
    compact: size.w < COMPACT_WIDTH || size.h < COMPACT_HEIGHT,
    portrait: size.h >= size.w,
    touch,
    insets,
  }
}
