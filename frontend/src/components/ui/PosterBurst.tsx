import { useViewport } from '../../hooks/useViewport'
import { RoughSunburst } from './RoughShapes'

/**
 * The burst every front-of-house screen is printed on.
 *
 * Fixed to the window rather than laid inside the page, for two reasons. A
 * waiting room with ten players in it scrolls, and a backdrop that scrolls with
 * it stops being a backdrop and becomes a very large decoration halfway down
 * the page. And a burst wide enough to reach the corners is wider than the
 * page, which on `.page-shell` — whose overflow is clipped sideways and not
 * downwards — would have handed a phone several hundred pixels of nothing to
 * scroll through.
 *
 * Everything drawn over it therefore needs to say so: see `.poster-content`,
 * which is the one class that puts a screen back in front of its own
 * background.
 */
export function PosterBurst() {
  const { w, h } = useViewport()
  // Half again as wide as the longest edge, so the rays are still rays at the
  // corners while the burst turns rather than sweeping their tips into view.
  const size = Math.round(Math.max(w, h) * 1.5)

  return (
    <div className="poster-burst" aria-hidden="true">
      <div
        className="poster-burst-wheel"
        style={{ width: size, height: size, marginLeft: -size / 2, marginTop: -size / 2 }}
      >
        <RoughSunburst size={size} />
      </div>
    </div>
  )
}
