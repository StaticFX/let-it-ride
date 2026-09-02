import { useState } from 'react'
import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { RoughBox } from './RoughShapes'

interface SketchButtonProps {
  children: React.ReactNode
  onClick?: () => void
  disabled?: boolean
  variant?: 'primary' | 'ghost'
  className?: string
}

export function SketchButton({ children, onClick, disabled, variant = 'primary', className = '' }: SketchButtonProps) {
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
      disabled={disabled}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => { setHovered(false); setPressed(false) }}
      onMouseDown={() => setPressed(true)}
      onMouseUp={() => setPressed(false)}
      onClick={onClick}
      className={className}
      style={{
        background: 'transparent',
        color: fg,
        border: 'none',
        padding: '10px 26px',
        minWidth: 110,
        fontFamily: theme.fontDisplay,
        fontSize: 24,
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
