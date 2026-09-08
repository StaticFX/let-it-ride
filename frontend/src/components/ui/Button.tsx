import { useState } from 'react'
import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { play } from '../../audio/sfx'
import { RoughBox } from './RoughShapes'

interface SketchButtonProps {
  children: React.ReactNode
  onClick?: () => void
  disabled?: boolean
  variant?: 'primary' | 'ghost'
  className?: string
  /** Stable handle for the end-to-end suite; the label is decoration. */
  testId?: string
}

export function SketchButton({ children, onClick, disabled, variant = 'primary', className = '', testId }: SketchButtonProps) {
  const [hovered, setHovered] = useState(false)
  const [pressed, setPressed] = useState(false)
  const { ref, size } = useElementSize<HTMLButtonElement>()

  const sw = theme.strokeWidth
  const isPrimary = variant === 'primary'
  const fg = isPrimary && hovered ? '#fff' : theme.ink
  const borderColor = isPrimary ? theme.actionAccent : theme.ink
  const fill = isPrimary && hovered ? theme.actionAccent : (isPrimary ? theme.cardFace : 'transparent')

  return (
    <button
      ref={ref}
      data-testid={testId}
      disabled={disabled}
      // Pointer events rather than mouse ones, so the press reads on a screen
      // being tapped as well as one being clicked — a synthesised mouse event
      // arrives after the finger has already come off, which is a button that
      // never looks pressed at all. `pointercancel` matters here: a tap that
      // turns into a scroll never gets its `up`.
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') setHovered(true) }}
      onPointerLeave={() => { setHovered(false); setPressed(false) }}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerCancel={() => { setPressed(false); setHovered(false) }}
      onClick={() => {
        // Every button in the game routes through here, so the click is wired
        // once rather than at each call site.
        play('click')
        onClick?.()
      }}
      className={className}
      style={{
        background: 'transparent',
        color: fg,
        border: 'none',
        // Read from the sheet so a narrow window can bring every button in the
        // game down a size at once — see the compact block in index.css.
        padding: 'var(--button-pad, 10px 26px)',
        minWidth: 'var(--button-min-width, 110px)',
        fontFamily: theme.fontDisplay,
        fontSize: 'var(--button-font-size, 24px)',
        letterSpacing: '0.01em',
        fontWeight: 700,
        cursor: disabled ? 'not-allowed' : 'pointer',
        position: 'relative',
        transform: pressed
          ? 'translate(2px, 2px) rotate(-1deg)'
          : hovered
            ? 'translate(-1px, -2px) rotate(-1deg)'
            : 'rotate(-1deg)',
        transition: 'transform 160ms cubic-bezier(.2,.9,.3,1.3), color 160ms',
        opacity: disabled ? 0.4 : 1,
        boxShadow: disabled ? 'none' : (pressed ? 'none' : `4px 4px 0 0 ${borderColor}`),
        borderRadius: 4,
      }}
    >
      {size.w > 0 && (
        <RoughBox
          width={size.w} height={size.h}
          stroke={borderColor} strokeWidth={sw} roughness={1.7}
          fill={fill === 'transparent' ? 'none' : fill}
          fillStyle="solid"
        />
      )}
      <span style={{ position: 'relative', zIndex: 2 }}>{children}</span>
    </button>
  )
}
