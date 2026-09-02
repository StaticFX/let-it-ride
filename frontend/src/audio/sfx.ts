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

export type SoundName = 'draw' | 'actionCard' | 'bust' | 'freeze' | 'flip7'

const SOURCES: Record<SoundName, string> = {
  draw: '/sounds/draw-card.m4a',
  actionCard: '/sounds/action-card.m4a',
  bust: '/sounds/bust.m4a',
  freeze: '/sounds/freeze.m4a',
  flip7: '/sounds/flip7.m4a',
}

/**
 * How far each sound may wander, as a fraction of its pitch. Cards are drawn
 * constantly so they need the most variation to stop sounding like a metronome;
 * flip 7 is the one moment in a round worth hearing exactly the same every time.
 */
const PITCH_SPREAD: Record<SoundName, number> = {
  draw: 0.14,
  actionCard: 0.09,
  bust: 0.06,
  freeze: 0.07,
  flip7: 0,
}

const GAIN: Record<SoundName, number> = {
  draw: 0.55,
  actionCard: 0.8,
  bust: 0.9,
  freeze: 0.8,
  flip7: 0.95,
}

let context: AudioContext | null = null
const buffers = new Map<SoundName, AudioBuffer>()
let loading: Promise<void> | null = null
let muted = localStorage.getItem(MUTE_KEY) === '1'

/** The same sound twice in a row is the one thing variation cannot hide. */
const lastRate = new Map<SoundName, number>()

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
  if (muted) return
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
  gain.gain.value = GAIN[name]

  source.connect(gain).connect(ctx.destination)
  source.start()
}
