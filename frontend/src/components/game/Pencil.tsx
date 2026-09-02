import { theme } from '../../theme'

interface PencilProps {
  style?: React.CSSProperties
}

export function Pencil({ style = {} }: PencilProps) {
  return (
    <svg width="140" height="18" viewBox="0 0 140 18" style={{
      pointerEvents: 'none',
      filter: 'drop-shadow(1px 2px 2px rgba(0,0,0,0.18))',
      ...style,
    }}>
      {/* Graphite tip */}
      <polygon points="0,9 10,5.5 10,12.5" fill="#2d2a25" />
      {/* Exposed wood taper */}
      <polygon points="10,5.5 18,4 18,14 10,12.5" fill="#c4944a" />
      <polygon points="10,5.5 18,4 18,14 10,12.5" fill="url(#pencilWoodGrain)" opacity="0.3" />
      {/* Pencil body — main */}
      <rect x="18" y="4" width="90" height="10" fill="#f2c53a" />
      {/* Body facets (hexagonal illusion) */}
      <rect x="18" y="4" width="90" height="3.5" fill="#f7d256" rx="0" />
      <rect x="18" y="10.5" width="90" height="3.5" fill="#ddb22e" rx="0" />
      {/* Printed text on pencil */}
      <text x="50" y="11.5" fill="rgba(0,0,0,0.12)"
        fontFamily={theme.fontDisplay} fontSize="5" fontWeight="700"
        letterSpacing="0.1em">LET IT RIDE</text>
      {/* Ferrule (metal band) */}
      <rect x="108" y="3.5" width="12" height="11" rx="1" fill="#b5ad9c" />
      {/* Ferrule crimps */}
      <rect x="110.5" y="3.5" width="1" height="11" fill="#9a9285" opacity="0.6" />
      <rect x="114" y="3.5" width="1" height="11" fill="#9a9285" opacity="0.6" />
      <rect x="117.5" y="3.5" width="1" height="11" fill="#9a9285" opacity="0.4" />
      {/* Eraser */}
      <rect x="120" y="4.2" width="14" height="9.6" rx="2.5" fill="#d4757a" />
      <rect x="120" y="4.2" width="14" height="3.5" rx="2" fill="#dc8a8e" />
      {/* Wood grain pattern */}
      <defs>
        <pattern id="pencilWoodGrain" width="4" height="14" patternUnits="userSpaceOnUse">
          <line x1="0" y1="0" x2="0" y2="14" stroke="#a07838" strokeWidth="0.3" opacity="0.5" />
          <line x1="2" y1="0" x2="2" y2="14" stroke="#a07838" strokeWidth="0.2" opacity="0.3" />
        </pattern>
      </defs>
    </svg>
  )
}
