import { useState } from 'react'
import { getVolume, isMuted, play, setMuted, setVolume } from '../../audio/sfx'
import { SketchSlider } from './SketchSlider'

/**
 * Volume, with the speaker doubling as a mute toggle. Dragging to zero mutes
 * and dragging back up unmutes, so the two controls never disagree.
 */
export function VolumeControl() {
  const [volume, setLocalVolume] = useState(getVolume)
  const [muted, setLocalMuted] = useState(isMuted)

  function changeVolume(next: number) {
    setVolume(next / 100)
    setLocalVolume(next / 100)
    setLocalMuted(isMuted())
    // Play something at the new level so the drag has feedback.
    if (next > 0) play('click')
  }

  function toggleMute() {
    const next = !muted
    setMuted(next)
    setLocalMuted(next)
    if (!next && volume === 0) {
      setVolume(0.7)
      setLocalVolume(0.7)
    }
    if (!next) play('click')
  }

  return (
    <div className="flex items-center gap-3 w-full">
      <button
        onClick={toggleMute}
        aria-label={muted ? 'turn sound on' : 'turn sound off'}
        className={`bg-transparent border-none cursor-pointer text-xl leading-none p-1 shrink-0 transition-opacity ${
          muted ? 'opacity-40' : 'opacity-90'
        }`}
      >
        {muted ? '🔇' : '🔊'}
      </button>
      <div className={`flex-1 transition-opacity ${muted ? 'opacity-40' : 'opacity-100'}`}>
        <SketchSlider
          label="volume"
          min={0}
          max={100}
          step={5}
          value={Math.round(volume * 100)}
          onChange={changeVolume}
        />
      </div>
    </div>
  )
}
