import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { RoughBox } from './RoughShapes'

export function SketchOption({ selected, onClick, children, testId }: {
  selected: boolean; onClick: () => void; children: React.ReactNode; testId?: string
}) {
  const ink = theme.ink
  const sw = theme.strokeWidth
  const { ref, size } = useElementSize<HTMLButtonElement>()

  return (
    <button ref={ref} onClick={onClick} data-testid={testId} data-selected={selected} style={{
      flex: 1, position: 'relative',
      padding: '10px 12px',
      fontFamily: theme.fontDisplay, fontSize: 17, fontWeight: 700,
      color: ink, cursor: 'pointer',
      background: 'transparent', border: 'none',
      transform: selected ? 'rotate(-1deg)' : 'rotate(0.5deg)',
      transition: 'transform 150ms ease',
      boxShadow: selected ? `3px 3px 0 0 ${ink}` : 'none',
      borderRadius: 4,
    }}>
      {size.w > 0 && (
        <RoughBox
          width={size.w} height={size.h}
          stroke={selected ? ink : `${ink}35`}
          strokeWidth={selected ? sw : sw * 0.7}
          roughness={selected ? 1.8 : 1.4}
          fill={selected ? `${ink}08` : 'none'}
          fillStyle="solid" boil={false}
        />
      )}
      <span style={{ position: 'relative', zIndex: 2 }}>{children}</span>
    </button>
  )
}
