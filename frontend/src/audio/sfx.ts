/**
 * Sound effects, played through the Web Audio API rather than <audio> elements.
 *
 * Two reasons: overlapping plays need their own source node (a single <audio>
 * restarts instead of layering, and a whole hand can be dealt inside a second),
 * and `playbackRate` on a buffer source is a real resample — so nudging it up
 * or down actually shifts the pitch. `<audio>` pitch-corrects by default, which
 * would give speed variation and no tonal variation at all.
 */

const MUTE_KEY = 'let-it-ride:muted'
const VOLUME_KEY = 'let-it-ride:volume'

export type SoundName =
  | 'draw'
  | 'actionCard'
  | 'bust'
  | 'freeze'
  | 'flip7'
  | 'goOut'
  | 'roundEnded'
  | 'click'
  | 'keystroke'

const SOURCES: Record<SoundName, string> = {
  draw: '/sounds/draw-card.m4a',
  actionCard: '/sounds/action-card.m4a',
  bust: '/sounds/bust.m4a',
  freeze: '/sounds/freeze.m4a',
  flip7: '/sounds/flip7.m4a',
  goOut: '/sounds/go-out.m4a',
  roundEnded: '/sounds/round-ended.m4a',
  click: '/sounds/button-click.m4a',
  keystroke: '/sounds/keystroke.m4a',
}

/**
 * How far each sound may wander, as a fraction of its pitch. The ones you hear
 * constantly — cards, keystrokes, clicks — need the most variation to stop
 * sounding like a metronome. Flip 7 is the one moment in a round worth hearing
 * exactly the same every time, and the round-end sting is a full stop.
 */
const PITCH_SPREAD: Record<SoundName, number> = {
  draw: 0.14,
  actionCard: 0.09,
  bust: 0.06,
  freeze: 0.07,
  flip7: 0,
  goOut: 0.08,
  roundEnded: 0,
  click: 0.11,
  keystroke: 0.18,
}

/** Per-sound trim, before the player's own volume. */
const GAIN: Record<SoundName, number> = {
  draw: 0.55,
  actionCard: 0.8,
  bust: 0.9,
  freeze: 0.8,
  flip7: 0.95,
  goOut: 0.7,
  roundEnded: 0.85,
  click: 0.45,
  keystroke: 0.3,
}

let context: AudioContext | null = null
const buffers = new Map<SoundName, AudioBuffer>()
let loading: Promise<void> | null = null
let muted = localStorage.getItem(MUTE_KEY) === '1'
let volume = readVolume()

/** The same sound twice in a row is the one thing variation cannot hide. */
const lastRate = new Map<SoundName, number>()

function readVolume(): number {
  const stored = Number(localStorage.getItem(VOLUME_KEY))
  return Number.isFinite(stored) && stored >= 0 && stored <= 1 ? stored : 0.7
}

function audioContext(): AudioContext | null {
  if (context) return context
  const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctor) return null
  context = new Ctor()
  return context
}

async function loadAll(ctx: AudioContext): Promise<void> {
  await Promise.all(
    (Object.keys(SOURCES) as SoundName[]).map(async (name) => {
      if (buffers.has(name)) return
      try {
        const response = await fetch(SOURCES[name])
        if (!response.ok) return
        buffers.set(name, await ctx.decodeAudioData(await response.arrayBuffer()))
      } catch {
        // A sound that will not decode simply never plays; the game is silent,
        // not broken.
      }
    }),
  )
}

/**
 * Browsers refuse to start an AudioContext until the page has been interacted
 * with, so this is called from the first click or keypress.
 */
export function unlockAudio(): void {
  const ctx = audioContext()
  if (!ctx) return
  if (ctx.state === 'suspended') void ctx.resume()
  loading ??= loadAll(ctx)
}

export function isMuted(): boolean {
  return muted
}

export function setMuted(next: boolean): void {
  muted = next
  localStorage.setItem(MUTE_KEY, next ? '1' : '0')
}

export function getVolume(): number {
  return volume
}

export function setVolume(next: number): void {
  volume = Math.min(1, Math.max(0, next))
  localStorage.setItem(VOLUME_KEY, String(volume))
  // Dragging to zero is the same intent as muting, and dragging back up is the
  // same as unmuting — otherwise the slider looks broken while muted.
  if (volume === 0) setMuted(true)
  else if (muted) setMuted(false)
}

function pitchFor(name: SoundName): number {
  const spread = PITCH_SPREAD[name]
  if (spread === 0) return 1

  let rate = 1
  for (let attempt = 0; attempt < 4; attempt++) {
    rate = 1 + (Math.random() * 2 - 1) * spread
    if (Math.abs(rate - (lastRate.get(name) ?? 0)) > spread * 0.4) break
  }
  lastRate.set(name, rate)
  return rate
}

export function play(name: SoundName): void {
  if (muted || volume === 0) return
  const ctx = audioContext()
  if (!ctx) return

  // First real sound doubles as the unlock if nothing has interacted yet.
  loading ??= loadAll(ctx)
  const buffer = buffers.get(name)
  if (!buffer || ctx.state !== 'running') return

  const source = ctx.createBufferSource()
  source.buffer = buffer
  source.playbackRate.value = pitchFor(name)

  const gain = ctx.createGain()
  gain.gain.value = GAIN[name] * volume

  source.connect(gain).connect(ctx.destination)
  source.start()
}
