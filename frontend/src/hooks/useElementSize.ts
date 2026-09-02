import { useEffect, useRef, useState } from 'react'

export function useElementSize<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })

  useEffect(() => {
    if (!ref.current) return
    const el = ref.current

    const update = () => {
      const r = el.getBoundingClientRect()
      setSize(prev => {
        const w = Math.round(r.width)
        const h = Math.round(r.height)
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
