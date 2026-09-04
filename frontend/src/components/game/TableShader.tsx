import { useEffect, useRef } from 'react'
import { theme } from '../../theme'

/**
 * The felt the game is played on, drawn by a fragment shader instead of a flat
 * colour.
 *
 * It sits at the very bottom of the board's z-stack and takes no pointer
 * events, so nothing above it — cards, seats, the scoreboard, the rough.js
 * frame — is touched by anything that happens here. That separation is the
 * whole point of doing it this way: an effect on the table can never bleed
 * onto the UI, because the UI is not in the canvas.
 *
 * `.game-shell` keeps its `background: var(--felt)`, which is what shows
 * through if WebGL2 is unavailable. [onReady] reports whether the canvas
 * actually took, so the caller can put its own fallback back on screen.
 *
 * [shock] drops a ripple at a point on the table. Nothing sends one at the
 * moment — a bust shakes the screen instead — but the channel is here for
 * whatever wants it next.
 */

/** How many ripples can be travelling at once. */
const MAX_SHOCKS = 3
/** How long one ripple takes to cross the table and fade out. */
const SHOCK_MS = 1400
/** How fast it travels, in CSS pixels per second. */
const SHOCK_SPEED = 920
/** Retina is nice on the grain and expensive everywhere else. */
const MAX_PIXEL_RATIO = 2

const VERTEX_SRC = `#version 300 es
void main() {
  // A single triangle covering the viewport — no buffers, no attributes.
  vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
}`

const FRAGMENT_SRC = `#version 300 es
precision highp float;

uniform vec2  uResolution;
uniform float uPixelRatio;
uniform float uTime;
uniform vec3  uFelt;
uniform vec3  uAccent;
uniform float uTurn;
uniform float uUrgency;
/** xy = origin in CSS pixels, z = age in seconds. A negative age is an empty slot. */
uniform vec3  uShocks[${MAX_SHOCKS}];

out vec4 fragColor;

float hash(vec2 p) {
  p = fract(p * vec2(127.31, 311.7));
  p += dot(p, p + 34.23);
  return fract(p.x * p.y);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i),                  hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 4; i++) {
    v += a * noise(p);
    p *= 2.03;
    a *= 0.5;
  }
  return v;
}

void main() {
  // Everything is reasoned about in CSS pixels so the table looks the same on
  // a retina display as it does on a projector.
  vec2 px = gl_FragCoord.xy / uPixelRatio;
  vec2 uv = gl_FragCoord.xy / uResolution;
  float aspect = uResolution.x / max(uResolution.y, 1.0);
  vec2 centred = (uv - 0.5) * vec2(aspect, 1.0);

  // The sheet: a fine tooth, long fibres pulled across it, and a broad
  // unevenness in the light. Only the last of the three moves, and slowly —
  // paper does not shimmer, and a grain that crawls reads as television static.
  float tooth  = noise(px * 1.7);
  float fibre  = fbm(vec2(px.x * 0.010, px.y * 0.34));
  float mottle = fbm(px * 0.0021 + vec2(uTime * 0.010, 0.0));

  vec3 col = uFelt;
  col *= 1.0 - (tooth  - 0.5) * 0.035;
  col *= 1.0 - (fibre  - 0.5) * 0.050;
  col *= 1.0 + (mottle - 0.5) * 0.070;

  // The corners of the table fall away from the light.
  col *= 1.0 - smoothstep(0.30, 0.92, length(centred)) * 0.10;

  // Your move — the accent breathes in from the edges, and breathes faster as
  // the clock runs out. This is the old .turn-vignette, as a gradient rather
  // than an inset box-shadow.
  float pulse = 0.5 + 0.5 * sin(uTime * mix(2.2, 7.0, uUrgency));
  float glow = smoothstep(0.26, 0.80, length(centred)) * uTurn * mix(0.45, 1.0, pulse);
  col = mix(col, uAccent, glow * mix(0.10, 0.20, uUrgency));

  // A ripple puts a crease in the felt at a point, and it travels outward.
  for (int i = 0; i < ${MAX_SHOCKS}; i++) {
    float age = uShocks[i].z;
    if (age < 0.0) continue;
    float life = 1.0 - clamp(age / ${(SHOCK_MS / 1000).toFixed(3)}, 0.0, 1.0);
    float d = distance(px, uShocks[i].xy);
    float ring = exp(-pow((d - age * ${SHOCK_SPEED.toFixed(1)}) / 85.0, 2.0));
    float core = exp(-(d * d) / (150.0 * 150.0)) * life;
    col *= 1.0 - (ring * life * 0.30 + core * 0.18);
    col = mix(col, uAccent, ring * life * 0.14);
  }

  fragColor = vec4(col, 1.0);
}`

/** `#rrggbb` to the 0..1 triple the shader wants. */
function rgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace('#', ''), 16)
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]
}

function compile(gl: WebGL2RenderingContext, type: number, src: string): WebGLShader | null {
  const shader = gl.createShader(type)
  if (!shader) return null
  gl.shaderSource(shader, src)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    console.warn('[TableShader]', gl.getShaderInfoLog(shader))
    gl.deleteShader(shader)
    return null
  }
  return shader
}

/** One ripple in flight. */
interface Shock {
  x: number
  y: number
  startedAt: number
}

/**
 * Where a ripple should start, and what set it off. A new [key] starts a new
 * ripple; the same key re-rendered does not.
 */
export interface TableShock {
  x: number
  y: number
  key: string
}

interface TableShaderProps {
  /** Whether the local player is the one on the clock. */
  myTurn: boolean
  /** How far into the closing stretch of that turn we are, 0..1. */
  urgency: number
  /** A ripple to drop on the table, or nothing for a still one. */
  shock?: TableShock | null
  /** Reports whether the canvas came up, so the caller can fall back. */
  onReady?: (live: boolean) => void
}

export function TableShader({ myTurn, urgency, shock = null, onReady }: TableShaderProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  /** The running canvas, once it is up. Null means there is nothing to talk to. */
  const engineRef = useRef<{ wake: () => void; addShock: (x: number, y: number) => void } | null>(null)
  const propsRef = useRef({ myTurn, urgency })
  const shocksRef = useRef<Shock[]>([])
  const seenShock = useRef<string | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const gl = canvas.getContext('webgl2', {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      powerPreference: 'low-power',
    })
    if (!gl) {
      onReady?.(false)
      return
    }

    const vs = compile(gl, gl.VERTEX_SHADER, VERTEX_SRC)
    const fs = compile(gl, gl.FRAGMENT_SHADER, FRAGMENT_SRC)
    const program = vs && fs ? gl.createProgram() : null
    if (!vs || !fs || !program) {
      onReady?.(false)
      return
    }
    gl.attachShader(program, vs)
    gl.attachShader(program, fs)
    gl.linkProgram(program)
    gl.deleteShader(vs)
    gl.deleteShader(fs)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      console.warn('[TableShader]', gl.getProgramInfoLog(program))
      gl.deleteProgram(program)
      onReady?.(false)
      return
    }

    gl.useProgram(program)
    const u = {
      resolution: gl.getUniformLocation(program, 'uResolution'),
      pixelRatio: gl.getUniformLocation(program, 'uPixelRatio'),
      time: gl.getUniformLocation(program, 'uTime'),
      felt: gl.getUniformLocation(program, 'uFelt'),
      accent: gl.getUniformLocation(program, 'uAccent'),
      turn: gl.getUniformLocation(program, 'uTurn'),
      urgency: gl.getUniformLocation(program, 'uUrgency'),
      shocks: gl.getUniformLocation(program, 'uShocks[0]'),
    }
    gl.uniform3fv(u.felt, rgb(theme.feltOuter))
    gl.uniform3fv(u.accent, rgb(theme.actionAccent))

    // Someone who has asked for less movement gets the paper and nothing else:
    // no breathing edge, no ripple, and no animation frames at all.
    const stillness = window.matchMedia('(prefers-reduced-motion: reduce)')
    let reduced = stillness.matches

    const started = performance.now()
    const packed = new Float32Array(MAX_SHOCKS * 3)
    let frame = 0
    let ratio = 1

    const resize = () => {
      ratio = Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO)
      const w = Math.max(1, Math.round(canvas.clientWidth * ratio))
      const h = Math.max(1, Math.round(canvas.clientHeight * ratio))
      if (canvas.width === w && canvas.height === h) return
      canvas.width = w
      canvas.height = h
      gl.viewport(0, 0, w, h)
    }

    const draw = () => {
      const now = performance.now()
      const height = canvas.clientHeight

      // Drop ripples that have run their course, and pack the rest. The shader
      // reads a CSS-pixel origin with y measured from the bottom, which is the
      // way round gl_FragCoord counts and the opposite of how a seat is placed.
      const live = shocksRef.current.filter((s) => now - s.startedAt < SHOCK_MS)
      shocksRef.current = live
      packed.fill(-1)
      for (let i = 0; i < Math.min(live.length, MAX_SHOCKS); i++) {
        packed[i * 3] = live[i].x
        packed[i * 3 + 1] = height - live[i].y
        packed[i * 3 + 2] = (now - live[i].startedAt) / 1000
      }

      gl.uniform2f(u.resolution, canvas.width, canvas.height)
      gl.uniform1f(u.pixelRatio, ratio)
      gl.uniform1f(u.time, reduced ? 0 : (now - started) / 1000)
      gl.uniform1f(u.turn, propsRef.current.myTurn ? 1 : 0)
      gl.uniform1f(u.urgency, propsRef.current.urgency)
      gl.uniform3fv(u.shocks, packed)
      gl.drawArrays(gl.TRIANGLES, 0, 3)
    }

    /**
     * Whether anything on the table is still moving. An idle table is drawn
     * once and then left alone — a full-screen fragment shader is a lot of
     * paint to be spending on a board nobody is acting on.
     */
    const moving = () => {
      return propsRef.current.myTurn || shocksRef.current.length > 0
    }

    const tick = () => {
      draw()
      frame = moving() ? requestAnimationFrame(tick) : 0
    }

    const wake = () => {
      if (frame) return
      if (!reduced && moving()) frame = requestAnimationFrame(tick)
      else draw()
    }

    /**
     * Starts a ripple, unless movement has been turned down — a ripple that is
     * recorded but never animated would sit on the felt as a dark blot, since
     * nothing would come back to advance it or clear it away.
     */
    const addShock = (x: number, y: number) => {
      if (reduced) return
      shocksRef.current = [
        ...shocksRef.current.slice(-(MAX_SHOCKS - 1)),
        { x, y, startedAt: performance.now() },
      ]
      wake()
    }

    engineRef.current = { wake, addShock }

    resize()
    draw()
    onReady?.(true)

    const observer = new ResizeObserver(() => {
      resize()
      wake()
    })
    observer.observe(canvas)

    const onStillnessChange = (e: MediaQueryListEvent) => {
      reduced = e.matches
      wake()
    }
    stillness.addEventListener('change', onStillnessChange)

    return () => {
      stillness.removeEventListener('change', onStillnessChange)
      observer.disconnect()
      if (frame) cancelAnimationFrame(frame)
      engineRef.current = null
      gl.deleteProgram(program)
      // Deliberately not WEBGL_lose_context here. StrictMode tears an effect
      // down and sets it straight back up on the same canvas, and a context
      // that has been lost on purpose is handed back by getContext() still
      // lost — the second setup would get a dead context, fail to compile, and
      // report the felt unavailable for the whole of development. The context
      // goes when React drops the canvas with it.
    }
    // Set up once. Everything that changes afterwards arrives through refs, so
    // a new turn or a new ripple never rebuilds the program.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // The turn changed hands, or the clock moved on — hand the new values to the
  // loop, repaint, and start it again if there is now something to animate.
  useEffect(() => {
    propsRef.current = { myTurn, urgency }
    engineRef.current?.wake()
  }, [myTurn, urgency])

  useEffect(() => {
    if (!shock) {
      seenShock.current = null
      return
    }
    if (shock.key === seenShock.current) return
    seenShock.current = shock.key
    engineRef.current?.addShock(shock.x, shock.y)
  }, [shock])

  return (
    <canvas
      ref={canvasRef}
      className="absolute inset-0 z-0 h-full w-full pointer-events-none"
      data-testid="table-felt"
      aria-hidden="true"
    />
  )
}
