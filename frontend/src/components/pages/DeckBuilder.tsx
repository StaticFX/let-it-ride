import { useMemo, useState } from 'react'
import type { Card as CardType, Catalog, DeckConfig } from '../../game/types'
import {
  countOfId,
  countOfNumber,
  deckProblem,
  deckSize,
  decodeDeck,
  encodeDeck,
  withCount,
  withNumber,
} from '../../game/deck'
import { PlayingCard } from '../cards/PlayingCard'

/** The number cards a built deck may hold, which is the house range. */
const NUMBER_RANGE = Array.from({ length: 14 }, (_, value) => value)

/** One card, how many are in, and the two buttons that change it. */
function Row({ card, count, max, onChange }: {
  card: CardType
  count: number
  max: number
  onChange: (count: number) => void
}) {
  return (
    <div
      className="flex items-center gap-2"
      data-testid="deck-row"
      data-card-id={card.defId ?? card.label}
      data-count={count}
    >
      <div className={count === 0 ? 'opacity-30 transition-opacity' : 'transition-opacity'}>
        <PlayingCard card={card} size="small" />
      </div>
      <div className="flex items-center gap-1">
        <button
          onClick={() => onChange(Math.max(0, count - 1))}
          disabled={count === 0}
          data-testid="deck-less"
          className={`display text-lg leading-none w-5 h-5 bg-transparent border-none ${
            count === 0 ? 'text-[var(--ink)]/20 cursor-default' : 'cursor-pointer'
          }`}
        >
          −
        </button>
        <span className="number text-lg w-4 text-center">{count}</span>
        <button
          onClick={() => onChange(Math.min(max, count + 1))}
          disabled={count >= max}
          data-testid="deck-more"
          className={`display text-lg leading-none w-5 h-5 bg-transparent border-none ${
            count >= max ? 'text-[var(--ink)]/20 cursor-default' : 'cursor-pointer'
          }`}
        >
          +
        </button>
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <>
      <label className="block mt-3">{title}</label>
      <div className="sketch-box-light flex flex-wrap gap-x-4 gap-y-1.5 p-2 mt-1 rounded">{children}</div>
    </>
  )
}

/**
 * Builds a deck a card at a time.
 *
 * Only offers what a deck may actually contain: the house number range, and the
 * cards that are cards — a house rule's prompt has a face and a name but is
 * never dealt, so it is not here.
 */
export function DeckBuilder({ deck, catalog, onChange }: {
  deck: DeckConfig
  catalog: Catalog
  onChange: (deck: DeckConfig) => void
}) {
  const [shared, setShared] = useState<string | null>(null)
  const limits = catalog.deckLimits
  const maxCopies = limits?.maxCopies ?? 20
  const problem = deckProblem(deck, catalog)

  const actions = useMemo(() => catalog.actions.filter((card) => card.deckable !== false), [catalog.actions])

  return (
    <div data-testid="deck-builder" data-size={deckSize(deck)} data-valid={!problem}>
      <div className="flex items-baseline justify-between">
        <label>your deck:</label>
        <span className="display text-lg">
          <span className={problem ? 'text-[var(--accent)]' : ''}>{deckSize(deck)}</span> cards
        </span>
      </div>

      {problem && (
        <p className="text-[var(--accent)] text-[13px] leading-snug mt-1" data-testid="deck-problem">
          {problem}
        </p>
      )}

      <Section title="numbers">
        {NUMBER_RANGE.map((value) => (
          <Row
            key={value}
            card={{ id: `build-n-${value}`, kind: 'number', label: String(value), value }}
            count={countOfNumber(deck, value)}
            max={maxCopies}
            onChange={(count) => onChange(withNumber(deck, value, count))}
          />
        ))}
      </Section>

      <Section title="action cards">
        {actions.map((card) => (
          <Row
            key={card.id}
            card={{ id: `build-a-${card.id}`, kind: 'action', label: card.name, value: 0, defId: card.id }}
            count={countOfId(deck.actionCards, card.id)}
            max={maxCopies}
            onChange={(count) => onChange({ ...deck, actionCards: withCount(deck.actionCards, card.id, count) })}
          />
        ))}
      </Section>

      <Section title="modifiers & protection">
        {catalog.passives.map((card) => (
          <Row
            key={card.id}
            card={{ id: `build-p-${card.id}`, kind: 'passive', label: card.name, value: 0, defId: card.id }}
            count={countOfId(deck.passiveCards, card.id)}
            max={maxCopies}
            onChange={(count) => onChange({ ...deck, passiveCards: withCount(deck.passiveCards, card.id, count) })}
          />
        ))}
      </Section>

      {/* A deck is small enough to travel as text, so it can be passed around
          without anything having to store it for you. */}
      <div className="flex items-center gap-3 mt-3">
        <button
          onClick={() => setShared(encodeDeck(deck))}
          data-testid="deck-share"
          className="bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] -rotate-1"
        >
          share this deck
        </button>
        <button
          onClick={() => {
            const text = window.prompt('paste a deck')
            const loaded = text && decodeDeck(text)
            if (loaded) onChange(loaded)
          }}
          data-testid="deck-load"
          className="bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] rotate-1"
        >
          paste one in
        </button>
      </div>

      {shared && (
        <textarea
          readOnly
          data-testid="deck-share-text"
          onFocus={(e) => e.currentTarget.select()}
          value={shared}
          className="sketch-box-light w-full mt-2 p-2 rounded text-[11px] font-mono break-all h-16"
        />
      )}
    </div>
  )
}
