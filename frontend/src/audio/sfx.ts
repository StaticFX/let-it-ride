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
const DEFAULT_VOLUME = 0.7

/** A sound asked for just before the buffers were ready still gets to play. */
const LATE_PLAY_GRACE_MS = 500

/** Bounded so a browser that never unlocks audio cannot accumulate requests. */
const MAX_QUEUED = 8

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
 * The samples are wildly different levels — measured RMS runs from 0.0056
 * (keystroke) to 0.566 (flip 7), a hundredfold spread — so these are not taste,
 * they are normalisation. Each gain brings its sample to roughly the same
 * loudness, capped so peaks stay under 1.0, and is then trimmed for how much
 * attention the sound deserves: a keystroke sits under everything, a flip 7 is
 * meant to be the loudest thing in the round.
 *
 * Picking these by ear-free guesswork is what made the click and keystroke
 * inaudible: they are the two quietest files and had been given the two
 * lowest gains.
 */
const GAIN: Record<SoundName, number> = {
  draw: 1.8,
  actionCard: 0.4,
  bust: 0.82,
  freeze: 1.4,
  flip7: 0.21,
  goOut: 3.1,
  roundEnded: 4.4,
  click: 3.0,
  keystroke: 3.9,
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

let context: AudioContext | null = null
const encoded = new Map<SoundName, ArrayBuffer>()
const buffers = new Map<SoundName, AudioBuffer>()
let prefetching: Promise<void> | null = null
let decoding: Promise<void> | null = null
let muted = localStorage.getItem(MUTE_KEY) === '1'
let volume = readVolume()

/** The same sound twice in a row is the one thing variation cannot hide. */
const lastRate = new Map<SoundName, number>()

/** Requests that arrived before the buffers were decoded. */
let queued: { name: SoundName; at: number }[] = []

function readVolume(): number {
  const raw = localStorage.getItem(VOLUME_KEY)
  // `Number(null)` is 0, so a browser that has never set this used to come back
  // silent — an unset key has to fall through to the default, not to zero.
  if (raw === null) return DEFAULT_VOLUME
  const stored = Number(raw)
  return Number.isFinite(stored) && stored >= 0 && stored <= 1 ? stored : DEFAULT_VOLUME
}

function audioContext(): AudioContext | null {
  if (context) return context
  const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctor) return null
  context = new Ctor()
  return context
}

/**
 * Downloads the samples. Deliberately not gated on a user gesture — only
 * starting the AudioContext is, and waiting for the gesture to *begin* fetching
 * meant the very click that unlocked audio was always silent.
 */
export function prefetchAudio(): void {
  prefetching ??= Promise.all(
    (Object.keys(SOURCES) as SoundName[]).map(async (name) => {
      try {
        const response = await fetch(SOURCES[name])
        if (response.ok) encoded.set(name, await response.arrayBuffer())
      } catch {
        // A sound that will not load simply never plays.
      }
    }),
  ).then(() => undefined)
}

async function decodeAll(ctx: AudioContext): Promise<void> {
  prefetchAudio()
  await prefetching
  await Promise.all(
    (Object.keys(SOURCES) as SoundName[]).map(async (name) => {
      if (buffers.has(name)) return
      const bytes = encoded.get(name)
      if (!bytes) return
      try {
        // decodeAudioData detaches the buffer, so hand it a copy.
        buffers.set(name, await ctx.decodeAudioData(bytes.slice(0)))
      } catch {
        // Undecodable on this browser; stay silent rather than break.
      }
    }),
  )
  flushQueued()
}

/**
 * Browsers refuse to start an AudioContext until the page has been interacted
 * with, so this is called from the first click or keypress.
 */
export function unlockAudio(): void {
  const ctx = audioContext()
  if (!ctx) return
  if (ctx.state === 'suspended') void ctx.resume()
  decoding ??= decodeAll(ctx)
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

function emit(name: SoundName, ctx: AudioContext, buffer: AudioBuffer): void {
  const source = ctx.createBufferSource()
  source.buffer = buffer
  source.playbackRate.value = pitchFor(name)

  const gain = ctx.createGain()
  gain.gain.value = GAIN[name] * volume

  source.connect(gain).connect(ctx.destination)
  source.start()
}

function flushQueued(): void {
  const ctx = context
  const now = Date.now()
  const pending = queued
  queued = []
  if (!ctx || ctx.state !== 'running' || muted || volume === 0) return
  for (const request of pending) {
    if (now - request.at > LATE_PLAY_GRACE_MS) continue
    const buffer = buffers.get(request.name)
    if (buffer) emit(request.name, ctx, buffer)
  }
}

export function play(name: SoundName): void {
  if (muted || volume === 0) return
  const ctx = audioContext()
  if (!ctx) return

  // The first real sound doubles as the unlock if nothing has interacted yet.
  decoding ??= decodeAll(ctx)
  if (ctx.state === 'suspended') void ctx.resume().then(flushQueued)

  const buffer = buffers.get(name)
  if (!buffer || ctx.state !== 'running') {
    // Still loading, or the context has not started. Hold onto it so the click
    // that unlocked audio is not the one click that makes no sound.
    if (queued.length < MAX_QUEUED) queued.push({ name, at: Date.now() })
    return
  }

  emit(name, ctx, buffer)
}
