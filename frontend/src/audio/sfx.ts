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
  | 'actionLanded'
  | 'bust'
  | 'freeze'
  | 'flip7'
  | 'goOut'
  | 'roundEnded'
  | 'timerRunningOut'
  | 'click'
  | 'keystroke'

/**
 * A sound is one file, or several to choose between. The click is the only one
 * you hear often enough for a single sample to start sounding like a machine —
 * pitch alone was carrying all of that variation, and two takes do it better
 * than any amount of resampling.
 */
const SOURCES: Record<SoundName, string | string[]> = {
  draw: '/sounds/draw-card.m4a',
  /** An action card came off the deck. */
  actionCard: '/sounds/action-card.m4a',
  /** ...and landed on somebody. */
  actionLanded: '/sounds/given-action-card-to-player.wav',
  bust: '/sounds/bust.m4a',
  freeze: '/sounds/freeze.m4a',
  flip7: '/sounds/flip7.m4a',
  goOut: '/sounds/go-out.m4a',
  roundEnded: '/sounds/round-ended.m4a',
  timerRunningOut: '/sounds/timer-less-than-10s.wav',
  click: ['/sounds/button-clicks/Click_1.wav', '/sounds/button-clicks/Click_2.wav'],
  keystroke: '/sounds/keystroke.m4a',
}

const NAMES = Object.keys(SOURCES) as SoundName[]

function variantsOf(name: SoundName): string[] {
  const source = SOURCES[name]
  return Array.isArray(source) ? source : [source]
}

/**
 * The samples are wildly different levels — measured RMS runs from 0.0079
 * (keystroke) to 0.566 (flip 7), a seventyfold spread — so these are not taste,
 * they are normalisation. Each gain brings its sample to roughly the same
 * loudness, capped so peaks stay under 1.0, and is then trimmed for how much
 * attention the sound deserves: a keystroke sits under everything, a flip 7 is
 * meant to be the loudest thing in the round.
 *
 * Picking these by ear-free guesswork is what made the click and keystroke
 * inaudible: they are the two quietest files and had been given the two
 * lowest gains.
 *
 * Measure against the mono buffer [toMono] produces, not the raw file. Four of
 * the samples were stereo with a dead right channel, and an RMS taken over the
 * interleaved samples counted that silence as signal — it read a factor of √2
 * low and earned those four a gain √2 too high. Playing out of one ear happened
 * to cancel it; centring them does not, so their gains came down to match.
 *
 * The three newest samples are recorded far hotter than the originals — RMS
 * 0.08 to 0.16 against the keystroke's 0.0079 — so they are the only ones with
 * a gain below 1. Each is set to the same loudness as the sound it stands
 * beside: the card landing with the bust, the click where the old click was,
 * and the clock under everything, because it plays for five seconds while the
 * table carries on.
 */
const GAIN: Record<SoundName, number> = {
  draw: 1.8,
  actionCard: 0.4,
  actionLanded: 0.48,
  bust: 0.82,
  freeze: 1.4,
  flip7: 0.21,
  goOut: 2.19,
  roundEnded: 3.11,
  timerRunningOut: 0.25,
  click: 0.27,
  keystroke: 2.76,
}

/**
 * Below this a channel is encoder noise rather than content. The dead sides of
 * the stereo samples peak at 0.0002; anything real is orders of magnitude up.
 */
const SILENT_PEAK = 0.001

/**
 * How far each sound may wander, as a fraction of its pitch. The ones you hear
 * constantly — cards, keystrokes, clicks — need the most variation to stop
 * sounding like a metronome. Flip 7 is the one moment in a round worth hearing
 * exactly the same every time, and the round-end sting is a full stop.
 */
const PITCH_SPREAD: Record<SoundName, number> = {
  draw: 0.14,
  actionCard: 0.09,
  actionLanded: 0.07,
  bust: 0.06,
  freeze: 0.07,
  flip7: 0,
  goOut: 0.08,
  roundEnded: 0,
  // A tune rather than a knock: resampling this one would be audible as
  // something being played wrong, not as the same thing said twice.
  timerRunningOut: 0,
  // Less than it was, because the two takes are now doing most of the work.
  click: 0.07,
  keystroke: 0.18,
}

let context: AudioContext | null = null
/** Downloaded bytes and decoded samples, one entry per take — see [SOURCES]. */
const encoded = new Map<string, ArrayBuffer>()
const buffers = new Map<SoundName, AudioBuffer[]>()
let prefetching: Promise<void> | null = null
let decoding: Promise<void> | null = null
let muted = localStorage.getItem(MUTE_KEY) === '1'
let volume = readVolume()

/** The same sound twice in a row is the one thing variation cannot hide. */
const lastRate = new Map<SoundName, number>()

/** ...and the same take twice in a row, for a sound that has more than one. */
const lastTake = new Map<SoundName, number>()

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
    NAMES.flatMap(variantsOf).map(async (url) => {
      try {
        const response = await fetch(url)
        if (response.ok) encoded.set(url, await response.arrayBuffer())
      } catch {
        // A sound that will not load simply never plays.
      }
    }),
  ).then(() => undefined)
}

/**
 * Collapses a sample to one channel, so it plays out of both ears.
 *
 * A mono buffer is copied to both outputs at full level on the way to the
 * speakers, which is what the samples that were already mono have always done.
 * The rest were not really stereo: they are mono recordings saved into a stereo
 * container with a silent right channel, so they only ever came out of the left.
 *
 * Averaging every channel would drop those 6dB, because the dead side would
 * drag the sum down — so channels carrying no signal are left out of the
 * divisor. A genuinely stereo sample still averages the normal way.
 */
function toMono(ctx: AudioContext, buffer: AudioBuffer): AudioBuffer {
  if (buffer.numberOfChannels === 1) return buffer

  const channels = Array.from({ length: buffer.numberOfChannels }, (_, c) => buffer.getChannelData(c))
  const live = channels.filter((data) => data.some((sample) => Math.abs(sample) > SILENT_PEAK))
  // An entirely silent sample has nothing to weigh; treat it as a normal mix.
  const contributing = live.length > 0 ? live : channels

  const mono = ctx.createBuffer(1, buffer.length, buffer.sampleRate)
  const out = mono.getChannelData(0)
  for (let i = 0; i < buffer.length; i++) {
    let sum = 0
    for (const data of contributing) sum += data[i]
    out[i] = sum / contributing.length
  }
  return mono
}

async function decodeAll(ctx: AudioContext): Promise<void> {
  prefetchAudio()
  await prefetching
  await Promise.all(
    NAMES.map(async (name) => {
      if (buffers.has(name)) return
      // A take that will not decode is left out rather than taking the whole
      // sound down with it: one of two clicks is still a click.
      const takes = await Promise.all(
        variantsOf(name).map(async (url) => {
          const bytes = encoded.get(url)
          if (!bytes) return null
          try {
            // decodeAudioData detaches the buffer, so hand it a copy.
            return toMono(ctx, await ctx.decodeAudioData(bytes.slice(0)))
          } catch {
            // Undecodable on this browser; stay silent rather than break.
            return null
          }
        }),
      )
      const decoded = takes.filter((take): take is AudioBuffer => take !== null)
      if (decoded.length > 0) buffers.set(name, decoded)
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

/** Which take to play, never the one that was just played. */
function takeFor(name: SoundName, takes: AudioBuffer[]): AudioBuffer {
  if (takes.length === 1) return takes[0]
  const previous = lastTake.get(name)
  let index = Math.floor(Math.random() * takes.length)
  if (index === previous) index = (index + 1) % takes.length
  lastTake.set(name, index)
  return takes[index]
}

function emit(name: SoundName, ctx: AudioContext, takes: AudioBuffer[]): void {
  const source = ctx.createBufferSource()
  source.buffer = takeFor(name, takes)
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
    const takes = buffers.get(request.name)
    if (takes) emit(request.name, ctx, takes)
  }
}

export function play(name: SoundName): void {
  if (muted || volume === 0) return
  const ctx = audioContext()
  if (!ctx) return

  // The first real sound doubles as the unlock if nothing has interacted yet.
  decoding ??= decodeAll(ctx)
  if (ctx.state === 'suspended') void ctx.resume().then(flushQueued)

  const takes = buffers.get(name)
  if (!takes || ctx.state !== 'running') {
    // Still loading, or the context has not started. Hold onto it so the click
    // that unlocked audio is not the one click that makes no sound.
    if (queued.length < MAX_QUEUED) queued.push({ name, at: Date.now() })
    return
  }

  emit(name, ctx, takes)
}
