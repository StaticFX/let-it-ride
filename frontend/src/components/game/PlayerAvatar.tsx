import { theme } from '../../theme'
import { RoughCircle } from '../ui/RoughShapes'

interface PlayerAvatarProps {
  initial: string
  active: boolean
  id: number
}

export function PlayerAvatar({ initial, active, id }: PlayerAvatarProps) {
  const size = 46
  const ink = theme.ink

  return (
    <div style={{
      width: size, height: size,
      position: 'relative',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
      animation: `sway ${2.8 + id * 0.21}s ease-in-out ${-id * 0.4}s infinite`,
    }}>
      <RoughCircle
        size={size} stroke={ink}
        strokeWidth={theme.strokeWidth}
        roughness={1.9}
        doubleStroke={!active}
        fill={active ? ink : 'none'}
        fillStyle={active ? 'solid' : undefined}
        boil={false}
      />
      <div style={{
        fontFamily: theme.fontDisplay, fontSize: 22, fontWeight: 700,
        color: active ? theme.cardFace : ink,
        lineHeight: 1, position: 'relative',
        transform: 'rotate(-3deg)',
      }}>{initial.toUpperCase()}</div>
      {active && (
        <div style={{
          position: 'absolute', top: -12, left: '50%',
          transform: 'translateX(-50%) rotate(-8deg)',
          fontFamily: theme.fontDisplay, fontSize: 18,
          color: theme.actionAccent, fontWeight: 700, lineHeight: 1,
          pointerEvents: 'none',
        }}>★</div>
      )}
    </div>
  )
}
