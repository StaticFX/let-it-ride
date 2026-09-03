import { useState, useRef, useCallback } from 'react'
import { theme } from '../../theme'
import { RoughBox } from './RoughShapes'

export function SketchSlider({ min, max, step, value, onChange, label, testId }: {
  min: number; max: number; step: number; value: number; onChange: (v: number) => void
  label?: string; testId?: string
}) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [isDragging, setIsDragging] = useState(false)
  const dragging = useRef(false)
  const ink = theme.ink

  const pct = ((value - min) / (max - min)) * 100

  const resolve = useCallback((clientX: number) => {
    if (!trackRef.current) return
    const rect = trackRef.current.getBoundingClientRect()
    const raw = (clientX - rect.left) / rect.width
    const clamped = Math.max(0, Math.min(1, raw))
    const snapped = Math.round((clamped * (max - min)) / step) * step + min
    onChange(Math.min(max, Math.max(min, snapped)))
  }, [min, max, step, onChange])

  const onPointerDown = useCallback((e: React.PointerEvent) => {
    dragging.current = true
    setIsDragging(true)
    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    resolve(e.clientX)
  }, [resolve])

  const onPointerMove = useCallback((e: React.PointerEvent) => {
    if (dragging.current) resolve(e.clientX)
  }, [resolve])

  const onPointerUp = useCallback(() => { dragging.current = false; setIsDragging(false) }, [])

  return (
    <div>
      {label !== undefined && (
        <div style={{
          fontFamily: theme.fontBody, fontSize: 15, color: theme.inkSoft,
          marginBottom: 4, display: 'flex', justifyContent: 'space-between',
        }}>
          <span>{label}</span>
          <span style={{ fontFamily: theme.fontDisplay, fontSize: 20, fontWeight: 700, color: ink }}>{value}</span>
        </div>
      )}
      <div
        ref={trackRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        data-testid={testId}
        data-value={value}
        data-min={min}
        data-max={max}
        style={{
          position: 'relative', height: 32, cursor: 'pointer',
          display: 'flex', alignItems: 'center',
          touchAction: 'none',
        }}
      >
        <div style={{
          position: 'absolute', left: 8, right: 8, top: '50%',
          height: 3, background: `${ink}25`, transform: 'translateY(-50%) rotate(-0.3deg)',
          borderRadius: 2,
        }} />
        <div style={{
          position: 'absolute', left: 8, top: '50%',
          width: `calc(${pct}% - 8px)`, height: 3,
          background: `${ink}60`, transform: 'translateY(-50%) rotate(-0.3deg)',
          borderRadius: 2,
        }} />
        <div style={{
          position: 'absolute',
          left: `calc(${pct}% - 12px)`, top: '50%',
          width: 24, height: 24,
          transform: 'translateY(-50%) rotate(-2deg)',
          transition: isDragging ? 'none' : 'left 80ms ease',
        }}>
          <RoughBox width={24} height={24}
            stroke={ink} strokeWidth={theme.strokeWidth} roughness={2.2}
            fill={theme.cardFace} fillStyle="solid" boil={false}
          />
          <div style={{
            position: 'absolute', inset: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <div style={{
              width: 6, height: 6, borderRadius: '50%',
              background: ink, transform: 'rotate(3deg)',
            }} />
          </div>
        </div>
      </div>
    </div>
  )
}
