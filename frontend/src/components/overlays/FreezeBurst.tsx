const FLAKES = [
  { dx: -46, dy: -30, size: 20, delay: 0 },
  { dx: 40, dy: -38, size: 15, delay: 90 },
  { dx: -18, dy: -52, size: 12, delay: 160 },
  { dx: 52, dy: 6, size: 17, delay: 60 },
  { dx: -54, dy: 14, size: 13, delay: 200 },
  { dx: 14, dy: -60, size: 18, delay: 130 },
  { dx: 30, dy: 34, size: 11, delay: 240 },
  { dx: -30, dy: 40, size: 14, delay: 180 },
]

/** Freeze: the seat frosts over and the target is out of the round. */
export function FreezeBurst({ x, y }: { x: number; y: number }) {
  return (
    <div className="fixed z-[210] pointer-events-none" style={{ left: x, top: y }}>
      <div className="absolute -translate-x-1/2 -translate-y-1/2 w-[190px] h-[190px] rounded-full frost-bloom" />

      {FLAKES.map((flake, i) => (
        <div
          key={i}
          className="absolute display font-bold text-[var(--frost)] frost-flake"
          style={{
            fontSize: flake.size,
            ['--flake-x' as string]: `${flake.dx}px`,
            ['--flake-y' as string]: `${flake.dy}px`,
            animationDelay: `${flake.delay}ms`,
          }}
        >
          ❄
        </div>
      ))}

      <div className="absolute -translate-x-1/2 display text-[26px] font-bold text-[var(--frost)] frost-word whitespace-nowrap"
        style={{ top: -74 }}>
        frozen!
      </div>
    </div>
  )
}
