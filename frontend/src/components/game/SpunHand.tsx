import { useLayoutEffect, useRef } from 'react'

/**
 * How long a hand takes to slide from the seat it came from to the one it
 * landed on. Has to stay inside `ANIMATION_TTL_MS.tableSpun`, which is what
 * holds the table while this runs.
 */
export const TABLE_SPIN_SLIDE_MS = 720

interface SpunHandProps {
  /**
   * The spin this hand is playing, or null when the table is not spinning. A
   * new id replays the slide; the same id must not.
   */
  spinId: string | null
  /** Where this hand came from, as an offset from where it now sits. */
  dx: number
  dy: number
  children: React.ReactNode
}

/**
 * Slides a hand in from the seat it was spun off.
 *
 * Same trick as [DealtCard], and for the same reason: the state that says every
 * hand has moved arrives in the *same* push as the event that says the table
 * spun, so by the time anything can animate the cards are already drawn at
 * their new seats. Rendering that and then animating gives a teleport followed
 * by a spin. Instead the hand is laid out where it belongs, thrown back to
 * where it came from for a single frame, and then released — so the only motion
 * on screen is the one trip.
 *
 * The offset is worked out per player id from the seats themselves, not from a
 * constant: SEAT_POSITIONS is indexed by the players around *you*, so the same
 * rotation is a different distance for every client at the table.
 */
export function SpunHand({ spinId, dx, dy, children }: SpunHandProps) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element || !spinId) return
    if (dx === 0 && dy === 0) return
    // Nothing here is load-bearing for the game state — the cards are already
    // where they belong — so somebody who has asked for less movement simply
    // gets them there.
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return

    /** Puts the hand back where the layout wants it. */
    const settle = () => {
      element.style.transform = ''
      element.style.opacity = ''
    }

    element.style.transition = 'none'
    element.style.transform = `translate(${dx}px, ${dy}px)`
    element.style.opacity = '0.7'

    // Reassigned rather than shadowed, so tearing down between the two frames
    // cannot leave a hand parked at somebody else's seat — see [DealtCard].
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        element.style.transition =
          `transform ${TABLE_SPIN_SLIDE_MS}ms cubic-bezier(.25,.85,.3,1.05), opacity ${TABLE_SPIN_SLIDE_MS / 2}ms ease-out`
        settle()
      })
    })

    return () => {
      cancelAnimationFrame(frame)
      element.style.transition = ''
      settle()
    }
  }, [spinId, dx, dy])

  return (
    <div ref={ref} data-testid="spun-hand" data-spinning={!!spinId}>
      {children}
    </div>
  )
}
