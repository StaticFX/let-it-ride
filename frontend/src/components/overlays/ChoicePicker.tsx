import { SketchButton } from '../ui/Button'

interface ChoicePickerProps {
  /** The card asking, for the test hooks. */
  cardDefId: string
  /** What the drawer is choosing between — "heads"/"tails", "left"/"right". */
  options: string[]
  /** The answer already given, if any. The card stays up until it resolves. */
  chosen: string | null
  /**
   * True while the answer is being held back for an animation the table is
   * still on. The pick is not lost — it goes out the moment the gate lifts —
   * but saying nothing at all would read as a dead button.
   */
  waiting: boolean
  onPick: (option: string) => void
  /**
   * The option under the cursor, or null when there is none. For a question
   * whose answer is easier to show than to word — see [SpinPreview], which
   * draws which way the table would turn while you are still deciding.
   */
  onHover?: (option: string | null) => void
  /** Screen position of the card the question belongs to. */
  x: number
  y: number
}

/**
 * The one overlay in the game that is actually clicked.
 *
 * Every other card is answered by pointing at a seat, but a card that asks a
 * question has nowhere to point — coin flip and spin the table both resolve on
 * their own drawer, so the seat is never in doubt and only the answer is. This
 * is offered whenever the card carries options, never on how many seats it
 * happens to advertise.
 */
export function ChoicePicker({ cardDefId, options, chosen, waiting, onPick, onHover, x, y }: ChoicePickerProps) {
  return (
    /* Full width and centred inside itself — see the same note on [Shop]. Left
       anchored at `x`, the row of options shrink-to-fits into what is left of
       the window, wins on min-content instead, and runs off *both* edges of a
       phone: "take it off someone" was cut mid-word at either end. */
    <div
      className="fixed inset-x-0 z-[230] flex flex-col items-center gap-2 choice-picker"
      data-testid="choice-picker"
      data-card-def-id={cardDefId}
      data-options={options.join(',')}
      data-chosen={chosen ?? ''}
      data-x={Math.round(x)}
      style={{ top: y }}
    >
      <div className="pick-target-label">{chosen ? `you called ${chosen}` : 'your call!'}</div>

      <div className="flex flex-wrap justify-center gap-3.5 max-w-[94vw]">
        {options.map((option) => (
          <div
            key={option}
            data-testid="choice-option"
            data-option={option}
            data-picked={chosen === option}
            // Focus as well as hover: the picker is two buttons, and somebody
            // tabbing to one is asking the same question as somebody pointing
            // at it.
            onMouseEnter={() => onHover?.(option)}
            onMouseLeave={() => onHover?.(null)}
            onFocus={() => onHover?.(option)}
            onBlur={() => onHover?.(null)}
            // ...and a finger going down, which is the only warning a touch
            // screen gets. [SpinPreview] exists to say what "left" and "right"
            // mean at a round table *before* the call is sent, and on a hover
            // this arrives with the cursor; on a tap the click would otherwise
            // be the first the player heard of it, and the preview would only
            // ever be seen answering a question already asked.
            onPointerDown={(e) => { if (e.pointerType !== 'mouse') onHover?.(option) }}
          >
            <SketchButton
              variant={chosen === option ? 'primary' : 'ghost'}
              disabled={!!chosen}
              onClick={() => onPick(option)}
              testId={`choice-${option}`}
            >
              {option}
            </SketchButton>
          </div>
        ))}
      </div>

      {chosen && waiting && <small className="display sway-mid">…sending it over</small>}
    </div>
  )
}
