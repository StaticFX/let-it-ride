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
export function ChoicePicker({ cardDefId, options, chosen, waiting, onPick, x, y }: ChoicePickerProps) {
  return (
    <div
      className="fixed z-[230] flex flex-col items-center gap-2 choice-picker"
      data-testid="choice-picker"
      data-card-def-id={cardDefId}
      data-options={options.join(',')}
      data-chosen={chosen ?? ''}
      style={{ left: x, top: y, transform: 'translateX(-50%)' }}
    >
      <div className="pick-target-label">{chosen ? `you called ${chosen}` : 'your call!'}</div>

      <div className="flex gap-3.5">
        {options.map((option) => (
          <div key={option} data-testid="choice-option" data-option={option} data-picked={chosen === option}>
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
