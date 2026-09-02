import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { RoughBox } from '../ui/RoughShapes'

export function PaperSheet({ children, rotation, zIndex, onClick, style = {} }: {
  children: React.ReactNode
  rotation: number
  zIndex: number
  onClick?: () => void
  style?: React.CSSProperties
}) {
  const { ref, size } = useElementSize<HTMLDivElement>()
  const ink = theme.ink
  const sw = theme.strokeWidth
  const lineColor = `${ink}10`

  return (
    <div
      ref={ref}
      onClick={onClick}
      style={{
        position: 'relative',
        padding: '48px 44px 56px',
        maxWidth: 640,
        width: '100%',
        minHeight: 600,
        transform: `rotate(${rotation}deg)`,
        transformOrigin: 'center center',
        zIndex,
        cursor: onClick ? 'pointer' : 'default',
        // Ruled lines as background
        background: `
          repeating-linear-gradient(
            transparent,
            transparent 31px,
            ${lineColor} 31px,
            ${lineColor} 32px
          ),
          linear-gradient(to bottom, ${theme.cardFace}, ${theme.cardFace})
        `,
        backgroundAttachment: 'local',
        // Wrinkle effect — layered inner shadows
        boxShadow: `
          inset 0 0 80px rgba(0,0,0,0.04),
          inset 20px 0 30px -15px rgba(0,0,0,0.05),
          inset -20px 0 30px -15px rgba(0,0,0,0.05),
          inset 0 20px 30px -15px rgba(0,0,0,0.03),
          inset 0 -20px 30px -15px rgba(0,0,0,0.03),
          6px 6px 0 0 ${ink}
        `,
        ...style,
      }}
    >
      {size.w > 0 && (
        <RoughBox width={size.w} height={size.h}
          stroke={ink} strokeWidth={sw} roughness={2.2} boil={false}
        />
      )}
      {/* Red margin line */}
      <div style={{
        position: 'absolute', top: 0, bottom: 0, left: 40,
        width: 2, background: 'rgba(192, 57, 43, 0.15)',
        zIndex: 0, pointerEvents: 'none',
      }} />
      {/* Wrinkle texture — diagonal creases */}
      <svg style={{
        position: 'absolute', inset: 0, width: '100%', height: '100%',
        pointerEvents: 'none', zIndex: 0,
      }}>
        <defs>
          <filter id="paper-wrinkle">
            <feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="4" seed="3" />
            <feDisplacementMap in="SourceGraphic" scale="2" />
          </filter>
        </defs>
        {/* Subtle diagonal crease lines */}
        <line x1="0" y1="30%" x2="100%" y2="33%" stroke={ink} strokeWidth="0.5" opacity="0.03" />
        <line x1="0" y1="62%" x2="100%" y2="60%" stroke={ink} strokeWidth="0.4" opacity="0.025" />
        <line x1="20%" y1="0" x2="22%" y2="100%" stroke={ink} strokeWidth="0.3" opacity="0.02" />
        <line x1="75%" y1="0" x2="73%" y2="100%" stroke={ink} strokeWidth="0.3" opacity="0.02" />
        {/* Worn edges — slightly darker at borders */}
        <rect x="0" y="0" width="100%" height="4" fill={ink} opacity="0.02" />
        <rect x="0" y="0" width="4" height="100%" fill={ink} opacity="0.015" />
      </svg>
      <div style={{ position: 'relative', zIndex: 1 }}>
        {children}
      </div>
    </div>
  )
}
