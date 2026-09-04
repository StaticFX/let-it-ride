import { findMark, useCatalog } from '../../state/gameStore'

interface MarkSlipProps {
  markId: string
  /** Its place in the seat's row, so a stack of them does not land as one. */
  index?: number
  /** Greyed back with the rest of a seat that is out of the round. */
  dimmed?: boolean
}

/**
 * An effect a player is under for the rest of the round.
 *
 * Deliberately not card-shaped. A mark cannot be stolen, swapped, struck or
 * discarded, and anything drawn as a card invites the player to try — so it is
 * a strip torn off the notepad and left by the seat instead, in the same ink
 * everything else is written in.
 */
export function MarkSlip({ markId, index = 0, dimmed = false }: MarkSlipProps) {
  const mark = findMark(useCatalog(), markId)
  // A mark this build has no face for is not guessed at.
  if (!mark) return null

  return (
    <div
      data-testid="mark-slip"
      data-mark-id={mark.id}
      title={mark.description}
      className="mark-slip"
      style={{
        opacity: dimmed ? 0.5 : 1,
        // Owned by the landing animation, so it travels as a variable — see
        // `.mark-slip` in index.css.
        ['--slip-tilt' as string]: `${(index % 2 === 0 ? -1 : 1) * (1.5 + index * 0.8)}deg`,
        animationDelay: `${index * 70}ms`,
      }}
    >
      <span className="mark-slip-sigil">{mark.sigil}</span>
      {mark.name}
    </div>
  )
}

/** Every mark a player carries, in the order the server sent them. */
export function MarkRow({ marks, dimmed }: { marks?: string[]; dimmed?: boolean }) {
  if (!marks || marks.length === 0) return null
  return (
    <div className="flex items-center justify-center gap-1 flex-wrap max-w-[190px]">
      {marks.map((id, i) => (
        <MarkSlip key={id} markId={id} index={i} dimmed={dimmed} />
      ))}
    </div>
  )
}
