import { useEffect, useRef } from 'react'
import type { CSSProperties } from 'react'
import rough from 'roughjs'
import { useBoilTick, useSeedOffset } from '../../hooks/useRoughHelpers'

function rcOpts(extra: Record<string, unknown> = {}) {
  return {
    bowing: 1.6,
    roughness: 1.7,
    disableMultiStroke: false,
    preserveVertices: false,
    ...extra,
  }
}

function drawInto(svg: SVGSVGElement, drawFn: (rc: ReturnType<typeof rough.svg>) => SVGElement | null) {
  while (svg.firstChild) svg.removeChild(svg.firstChild)
  const rc = rough.svg(svg)
  const node = drawFn(rc)
  if (node) svg.appendChild(node)
}

// --- RoughBox ---
interface RoughBoxProps {
  width: number
  height: number
  stroke?: string
  strokeWidth?: number
  roughness?: number
  fill?: string
  fillStyle?: string
  fillWeight?: number
  hachureGap?: number
  hachureAngle?: number
  dashed?: boolean
  boil?: boolean
  style?: CSSProperties
}

export function RoughBox({
  width, height,
  stroke = '#15140f', strokeWidth = 2.2, roughness = 1.7,
  fill = 'none', fillStyle = 'solid', fillWeight = 1,
  hachureGap, hachureAngle,
  dashed = false, boil = true, style = {},
}: RoughBoxProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const seedOffset = useSeedOffset()
  const t = useBoilTick(boil)
  const seed = (seedOffset + t * 13) & 0xffff

  useEffect(() => {
    if (!svgRef.current || width <= 0 || height <= 0) return
    const pad = strokeWidth + 2
    const w = Math.max(1, width - 2 * pad)
    const h = Math.max(1, height - 2 * pad)
    drawInto(svgRef.current, (rc) =>
      rc.rectangle(pad, pad, w, h, rcOpts({
        stroke, strokeWidth, roughness, seed,
        fill: fill === 'none' ? undefined : fill,
        fillStyle, fillWeight, hachureGap, hachureAngle,
        strokeLineDash: dashed ? [6, 5] : undefined,
      }))
    )
  }, [width, height, stroke, strokeWidth, roughness, fill, fillStyle, fillWeight, hachureGap, hachureAngle, dashed, seed])

  return (
    <svg ref={svgRef} width={width} height={height}
      viewBox={`0 0 ${width} ${height}`}
      style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', overflow: 'visible', ...style }}
    />
  )
}

// --- RoughCircle ---
interface RoughCircleProps {
  size: number
  stroke?: string
  strokeWidth?: number
  roughness?: number
  fill?: string
  fillStyle?: string
  doubleStroke?: boolean
  boil?: boolean
  style?: CSSProperties
}

export function RoughCircle({
  size, stroke = '#15140f', strokeWidth = 2.2, roughness = 1.8,
  fill = 'none', fillStyle = 'solid', doubleStroke = false,
  boil = true, style = {},
}: RoughCircleProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const seedOffset = useSeedOffset()
  const t = useBoilTick(boil)
  const seed = (seedOffset + t * 19) & 0xffff

  useEffect(() => {
    if (!svgRef.current) return
    const d = size - strokeWidth * 2 - 2
    drawInto(svgRef.current, (rc) => {
      const g = document.createElementNS('http://www.w3.org/2000/svg', 'g')
      g.appendChild(rc.ellipse(size / 2, size / 2, d, d * 0.98, rcOpts({
        stroke, strokeWidth, roughness, seed,
        fill: fill === 'none' ? undefined : fill, fillStyle,
      })))
      if (doubleStroke) {
        g.appendChild(rc.ellipse(size / 2 + 0.6, size / 2 - 0.4, d * 0.97, d * 0.99, rcOpts({
          stroke, strokeWidth: strokeWidth * 0.55,
          roughness: roughness * 1.2,
          seed: (seed + 7) & 0xffff,
        })))
      }
      return g
    })
  }, [size, stroke, strokeWidth, roughness, fill, fillStyle, doubleStroke, seed])

  return (
    <svg ref={svgRef} width={size} height={size}
      viewBox={`0 0 ${size} ${size}`}
      style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', overflow: 'visible', ...style }}
    />
  )
}

// --- RoughSeal ---

export type SealShape = 'circle' | 'hexagon' | 'shield' | 'scallop'

interface RoughSealProps {
  size: number
  shape?: SealShape
  stroke?: string
  strokeWidth?: number
  roughness?: number
  boil?: boolean
  style?: CSSProperties
}

/**
 * The stamp a passive card's sigil is struck inside. The shape is doing the
 * same job the colour is — telling one card from another before either is read
 * — so it is drawn from the catalog rather than being the same ring every time.
 *
 * Struck twice, slightly out of register, the way a real stamp lands.
 */
export function RoughSeal({
  size, shape = 'circle', stroke = '#15140f', strokeWidth = 2.2,
  roughness = 2, boil = true, style = {},
}: RoughSealProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const seedOffset = useSeedOffset()
  const t = useBoilTick(boil)
  const seed = (seedOffset + t * 23) & 0xffff

  useEffect(() => {
    if (!svgRef.current || size <= 0) return
    const c = size / 2
    const r = c - strokeWidth - 1

    // Every shape is written as a closed path so the double strike is one code
    // path — rough.js wobbles the outline either way.
    const path = (radius: number): string => {
      switch (shape) {
        case 'hexagon':
        case 'scallop': {
          // A token's flat sides, or the same points pulled into scallops.
          const points = shape === 'hexagon' ? 6 : 8
          const scalloped = shape === 'scallop'
          let d = ''
          for (let i = 0; i < points; i++) {
            const a = (i / points) * Math.PI * 2 - Math.PI / 2
            const x = c + Math.cos(a) * radius
            const y = c + Math.sin(a) * radius
            if (i === 0) {
              d += `M ${x} ${y}`
            } else if (scalloped) {
              // Bulge outward between the points, so the edge reads as lace.
              const prev = ((i - 1) / points) * Math.PI * 2 - Math.PI / 2
              const mid = (prev + a) / 2
              const bulge = radius * 1.24
              d += ` Q ${c + Math.cos(mid) * bulge} ${c + Math.sin(mid) * bulge} ${x} ${y}`
            } else {
              d += ` L ${x} ${y}`
            }
          }
          return `${d} Z`
        }
        case 'shield': {
          const w = radius * 0.94
          const top = c - radius * 0.92
          const shoulder = c + radius * 0.15
          const point = c + radius * 1.0
          return [
            `M ${c - w} ${top}`,
            `L ${c + w} ${top}`,
            `L ${c + w} ${shoulder}`,
            `Q ${c + w} ${point} ${c} ${point}`,
            `Q ${c - w} ${point} ${c - w} ${shoulder}`,
            'Z',
          ].join(' ')
        }
        default:
          return ''
      }
    }

    drawInto(svgRef.current, (rc) => {
      const g = document.createElementNS('http://www.w3.org/2000/svg', 'g')
      const strike = (radius: number, width: number, s: number) =>
        shape === 'circle'
          ? rc.ellipse(c, c, radius * 2, radius * 1.96, rcOpts({ stroke, strokeWidth: width, roughness, seed: s }))
          : rc.path(path(radius), rcOpts({ stroke, strokeWidth: width, roughness, seed: s, fill: undefined }))

      g.appendChild(strike(r, strokeWidth, seed))
      // The second, lighter strike a hair off the first.
      g.appendChild(strike(r * 0.96, strokeWidth * 0.55, (seed + 7) & 0xffff))
      return g
    })
  }, [size, shape, stroke, strokeWidth, roughness, seed])

  return (
    <svg ref={svgRef} width={size} height={size}
      viewBox={`0 0 ${size} ${size}`}
      style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', overflow: 'visible', ...style }}
    />
  )
}

// --- RoughSquiggle ---
interface RoughSquiggleProps {
  width: number
  height: number
  stroke?: string
  strokeWidth?: number
  roughness?: number
  amplitude?: number
  segments?: number
  boil?: boolean
  style?: CSSProperties
}

export function RoughSquiggle({
  width, height, stroke = '#15140f', strokeWidth = 2, roughness = 1.5,
  amplitude = 2, segments = 6, boil = true, style = {},
}: RoughSquiggleProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const seedOffset = useSeedOffset()
  const t = useBoilTick(boil)
  const seed = (seedOffset + t * 7) & 0xffff

  useEffect(() => {
    if (!svgRef.current) return
    const y = height / 2
    const step = width / segments
    let d = `M 2 ${y}`
    for (let i = 1; i <= segments; i++) {
      const cx = (i - 0.5) * step
      const cy = y + (i % 2 === 0 ? -amplitude : amplitude)
      const ex = i * step
      d += ` Q ${cx} ${cy} ${ex} ${y}`
    }
    drawInto(svgRef.current, (rc) =>
      rc.path(d, rcOpts({ stroke, strokeWidth, roughness, seed, fill: 'none' }))
    )
  }, [width, height, stroke, strokeWidth, roughness, amplitude, segments, seed])

  return (
    <svg ref={svgRef} width={width} height={height}
      viewBox={`0 0 ${width} ${height}`}
      style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', overflow: 'visible', ...style }}
    />
  )
}
