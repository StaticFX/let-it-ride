import type { CSSProperties } from 'react'

/**
 * Spin the table: the mark over the middle of the felt while every hand slides
 * one seat. The hands themselves are the animation — see [SpunHand] — this only
 * says which way the table went.
 */
export function TableSwirl({ direction, x, y }: { direction: string; x: number; y: number }) {
  const left = direction === 'left'

  return (
    <div
      className="fixed z-[205] pointer-events-none flex flex-col items-center gap-1.5 table-swirl"
      data-testid="table-swirl"
      data-direction={direction}
      style={{ left: x, top: y, transform: 'translate(-50%, -50%)' }}
    >
      <div
        className="table-swirl-arrow display text-[64px] font-bold text-[var(--accent)] leading-none"
        style={{ '--swirl-turn': left ? '-300deg' : '300deg' } as CSSProperties}
      >
        {left ? '↺' : '↻'}
      </div>
      <div className="display text-[22px] font-bold whitespace-nowrap">the table spins {direction}!</div>
    </div>
  )
}
