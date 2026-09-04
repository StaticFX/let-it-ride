import { useState } from 'react'
import { PlayingCard } from '../cards/PlayingCard'
import { cardName, type Palette } from './devSetup'

type Row = 'numbers' | 'actions' | 'passives'

const ROWS: { id: Row; label: string }[] = [
  { id: 'numbers', label: 'numbers' },
  { id: 'actions', label: 'actions' },
  { id: 'passives', label: 'modifiers' },
]

interface DevCardPickerProps {
  palette: Palette
  /** Called with the card's name — what the server matches on. */
  onPick: (name: string) => void
  onClose?: () => void
  testId?: string
}

/**
 * A card to click. One row of the deck at a time so the panel does not turn into
 * a wall of faces — the numbers are what you reach for nine times out of ten.
 */
export function DevCardPicker({ palette, onPick, onClose, testId }: DevCardPickerProps) {
  const [row, setRow] = useState<Row>('numbers')
  const cards = palette[row]

  return (
    <div className="mt-2 rounded border border-dashed border-[var(--ink-soft)] p-2" data-testid={testId}>
      <div className="mb-2 flex items-center gap-2">
        {ROWS.map((each) => (
          <button
            key={each.id}
            onClick={() => setRow(each.id)}
            data-testid={`dev-picker-${each.id}`}
            className={`display cursor-pointer rounded border px-2 py-0.5 text-sm ${
              row === each.id
                ? 'border-[var(--ink)] bg-[var(--ink)] text-[var(--card-face)]'
                : 'border-[var(--ink-soft)] bg-transparent text-[var(--ink-soft)]'
            }`}
          >
            {each.label}
          </button>
        ))}
        {onClose && (
          <button
            onClick={onClose}
            data-testid="dev-picker-close"
            className="ml-auto cursor-pointer border-none bg-transparent text-sm text-[var(--ink-soft)]"
          >
            close
          </button>
        )}
      </div>

      <div className="flex max-h-[164px] flex-wrap gap-1 overflow-y-auto">
        {cards.map((card) => (
          <button
            key={card.id}
            onClick={() => onPick(cardName(card))}
            data-testid="dev-pick-card"
            data-card-name={cardName(card)}
            title={cardName(card)}
            className="cursor-pointer border-none bg-transparent p-0"
          >
            <PlayingCard card={card} size="small" />
          </button>
        ))}
        {cards.length === 0 && <p className="text-muted italic">nothing in this deck</p>}
      </div>
    </div>
  )
}
