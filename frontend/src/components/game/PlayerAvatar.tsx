import { theme } from '../../theme'
import { RoughCircle } from '../ui/RoughShapes'

interface PlayerAvatarProps {
  initial: string
  active: boolean
  /**
   * It is this player's move. Kept apart from `active`, which also covers being
   * dealt to and being hovered as a target — this is the one that answers
   * "whose turn is it", and it is the only one that gets the accent ring.
   */
  onTurn?: boolean
  id: number
}

/** The pen ring's clearance around the avatar, on every side. */
const RING_GAP = 10

export function PlayerAvatar({ initial, active, onTurn = false, id }: PlayerAvatarProps) {
  const size = 46
  const ring = size + RING_GAP * 2
  const ink = theme.ink

  return (
    <div style={{
      width: size, height: size,
      position: 'relative',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
      animation: `sway ${2.8 + id * 0.21}s ease-in-out ${-id * 0.4}s infinite`,
    }}>
      {/* Circled in accent, the way you would ring a name on the pad. Drawn
          first so the letter inside it is never covered. */}
      {onTurn && (
        <div className="turn-ring" style={{ top: -RING_GAP, left: -RING_GAP, width: ring, height: ring }}>
          <RoughCircle
            size={ring} stroke={theme.actionAccent}
            strokeWidth={theme.strokeWidth * 1.1}
            roughness={2.6}
            boil={false}
          />
        </div>
      )}
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
