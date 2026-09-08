import { useState } from 'react'
import { isMuted, setMuted } from '../../audio/sfx'

export function SoundToggle({ className = '' }: { className?: string }) {
  const [muted, setLocalMuted] = useState(isMuted)

  function toggle() {
    const next = !muted
    setMuted(next)
    setLocalMuted(next)
  }

  return (
    <button
      onClick={toggle}
      // No `title`: a tooltip is a thing a cursor can ask for and this control
      // is mostly tapped. The label is what a screen reader reads and the icon
      // is what everyone else does.
      aria-label={muted ? 'turn sound on' : 'turn sound off'}
      className={`tap-target bg-transparent border-none cursor-pointer display text-xl leading-none p-1 transition-opacity ${
        muted ? 'opacity-40' : 'opacity-90'
      } ${className}`}
    >
      {muted ? '🔇' : '🔊'}
    </button>
  )
}
