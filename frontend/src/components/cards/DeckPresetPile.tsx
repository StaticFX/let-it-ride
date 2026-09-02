import { CardBack } from './CardBack'
import type { DeckPresetInfo } from '../../game/types'

const STACK = [
  { top: -9, left: 6, rot: 'rotate(2.5deg)', z: 'z-[1]' },
  { top: -6, left: 4, rot: 'rotate(0deg)', z: 'z-[2]' },
  { top: -3, left: 2, rot: 'rotate(-2.5deg)', z: 'z-[3]' },
  { top: 0, left: 0, rot: 'rotate(-5deg)', z: 'z-[4]' },
]

export function DeckPresetPile({ preset, selected, onClick }: {
  preset: DeckPresetInfo
  selected: boolean
  onClick: () => void
}) {
  return (
    <button onClick={onClick} className={`
      flex flex-col items-center gap-2 px-2 py-2
      bg-transparent border-none cursor-pointer
      transition-all duration-200 ease-out
      ${selected ? 'scale-108 -translate-y-1 opacity-100' : 'scale-100 opacity-70 grayscale-[0.3]'}
    `}>
      <div className="relative w-[56px] h-[84px]">
        {STACK.map((off, i) => (
          <div key={i} className={`absolute ${off.z}`} style={{ top: off.top, left: off.left, transform: off.rot }}>
            <CardBack size="small" style={{ animation: 'none' }} />
          </div>
        ))}
        <div className={`
          absolute -bottom-1 -right-1.5 z-10
          font-[var(--font-display)] text-[10px] font-bold
          text-[var(--card-face)] rounded-full px-1.5 leading-[14px]
          transition-colors duration-200
          ${selected ? 'bg-[var(--accent)]' : 'bg-[var(--ink)]'}
        `}>{preset.cardCount}</div>
      </div>

      <div className={`
        font-[var(--font-display)] text-sm font-bold
        leading-tight text-center max-w-[80px]
        transition-colors duration-200
        ${selected ? 'text-[var(--ink)]' : 'text-[var(--ink-soft)]'}
      `}>{preset.name}</div>

      {selected && <div className="w-1.5 h-1.5 rounded-full bg-[var(--accent)] -mt-1" />}
    </button>
  )
}
