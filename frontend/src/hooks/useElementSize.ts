import { useEffect, useRef, useState } from 'react'

export function useElementSize<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })

  useEffect(() => {
    if (!ref.current) return
    const el = ref.current

    // The layout box rather than the painted one. Every caller measures a box
    // in order to draw a hand-drawn frame *inside* it, and that frame is drawn
    // in the element's own coordinates — so a `scale()` on an ancestor must not
    // be counted twice. `getBoundingClientRect` includes it and drew the
    // scoreboard's border at four fifths of its own height the first time a
    // table of ten shrank it.
    const update = () => {
      setSize(prev => {
        const w = el.offsetWidth
        const h = el.offsetHeight
        if (prev.w === w && prev.h === h) return prev
        return { w, h }
      })
    }

    update()
    const observer = new ResizeObserver(update)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  return { ref, size }
}
