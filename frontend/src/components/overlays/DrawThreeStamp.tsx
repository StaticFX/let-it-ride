/** Draw 3: three cards thump down on the target before the draws start. */
export function DrawThreeStamp({ x, y }: { x: number; y: number }) {
  return (
    <div className="fixed z-[210] pointer-events-none -translate-x-1/2 -translate-y-1/2" style={{ left: x, top: y }}>
      <div className="relative w-[120px] h-[120px]">
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className="absolute left-1/2 top-1/2 w-[34px] h-[48px] rounded-[3px] bg-[var(--card-face)] border-[2.5px] border-[var(--ink)] draw-three-card"
            style={{
              ['--card-x' as string]: `${(i - 1) * 30}px`,
              ['--card-rot' as string]: `${(i - 1) * 11}deg`,
              animationDelay: `${i * 130}ms`,
            }}
          />
        ))}
      </div>
      <div className="absolute left-1/2 -translate-x-1/2 -top-2 display text-[30px] font-bold text-[var(--accent)] draw-three-word whitespace-nowrap">
        draw 3!
      </div>
    </div>
  )
}
