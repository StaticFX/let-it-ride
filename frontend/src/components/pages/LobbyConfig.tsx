import { useState } from 'react'
import { useCatalog } from '../../state/gameStore'
import type { Card as CardType, DeckConfig, GameConfig } from '../../game/types'
import { CUSTOM_DECK_ID } from '../../game/types'
import { DeckBuilder } from './DeckBuilder'
import { PlayingCard } from '../cards/PlayingCard'
import { SketchSlider } from '../ui/SketchSlider'
import { SketchOption } from '../ui/SketchOption'
import { DeckPresetPile } from '../cards/DeckPresetPile'
import { RoughSquiggle } from '../ui/RoughShapes'

interface LobbyConfigProps {
  config: GameConfig
  onChange: (config: GameConfig) => void
}

function Separator() {
  return (
    <div className="relative h-2 my-3.5">
      <RoughSquiggle
        width={280}
        height={8}
        stroke="rgba(31,28,20,0.19)"
        strokeWidth={0.8}
        amplitude={1}
        segments={10}
        roughness={1.4}
        boil={false}
      />
    </div>
  )
}

/** The last deck this browser built, so it survives leaving the table. */
const BUILT_DECK_KEY = 'let-it-ride:deck'

export function LobbyConfig({ config, onChange }: LobbyConfigProps) {
  const catalog = useCatalog()
  const [inspectedCard, setInspectedCard] = useState<CardType | null>(null)

  if (!catalog) return <p className="text-muted text-center">loading the deck…</p>

  // Undefined for a deck somebody built, which is the point of the id.
  const preset = catalog.decks.find((d) => d.id === config.deckPresetId)
  const building = config.deckPresetId === CUSTOM_DECK_ID

  function patch(next: Partial<GameConfig>) {
    onChange({ ...config, ...next })
  }

  function selectDeck(deckId: string) {
    const deck = catalog!.decks.find((d) => d.id === deckId)
    if (!deck) return
    patch({ deckPresetId: deck.id, deck: deck.deck })
  }

  /**
   * Starts a build from whatever is on the table, so nobody begins with an
   * empty deck and a list of everything it is missing.
   */
  function startBuilding() {
    const from = preset?.deck ?? config.deck
    patch({ deckPresetId: CUSTOM_DECK_ID, deck: from })
  }

  function editDeck(deck: DeckConfig) {
    patch({ deckPresetId: CUSTOM_DECK_ID, deck })
    localStorage.setItem(BUILT_DECK_KEY, JSON.stringify(deck))
  }

  function toggleRule(ruleId: string) {
    const active = config.ruleIds.includes(ruleId)
    patch({ ruleIds: active ? config.ruleIds.filter((r) => r !== ruleId) : [...config.ruleIds, ruleId] })
  }

  return (
    <>
      <div className="sketch-box rounded p-4 relative">
        <h2 className="mb-3.5 -rotate-1">~ settings ~</h2>

        {/* ── Deck ── */}
        <label>deck:</label>
        <div className="flex gap-1 mb-3 mt-1 overflow-x-auto pb-1 justify-center flex-wrap">
          {catalog.decks.map((deck) => (
            <DeckPresetPile
              key={deck.id}
              preset={deck}
              selected={preset?.id === deck.id}
              onClick={() => selectDeck(deck.id)}
            />
          ))}
          <button
            onClick={startBuilding}
            data-testid="build-own-deck"
            data-selected={building}
            className={`display text-base px-3 self-center bg-transparent border-none cursor-pointer -rotate-1 ${
              building ? 'text-[var(--accent)]' : 'text-[var(--ink-soft)]'
            }`}
          >
            build
            <br />
            your own
          </button>
        </div>
        <p className="text-muted text-center text-[13px] mb-3 leading-snug italic">
          {preset?.description ?? 'a deck of your own'}
        </p>

        {building ? (
          <DeckBuilder deck={config.deck} catalog={catalog} onChange={editDeck} />
        ) : (
          <>
            <label>cards in {preset?.name}:</label>
            <div className="sketch-box-light flex flex-wrap gap-1.5 p-2 mt-1 mb-2 rounded">
              {preset?.contents.map((entry) => (
                <button
                  key={entry.card.id}
                  onClick={() => setInspectedCard(entry.card)}
                  className="relative bg-transparent border-none p-0 cursor-pointer transition-transform duration-100 hover:scale-110 hover:-rotate-2"
                >
                  <PlayingCard card={entry.card} size="small" />
                  <span className="absolute -bottom-0.5 -right-0.5 z-10 display text-[10px] text-[var(--card-face)] bg-[var(--ink)] rounded-full px-1 leading-[14px] min-w-[16px] text-center">
                    {entry.count}x
                  </span>
                </button>
              ))}
            </div>
          </>
        )}

        <Separator />

        {/* ── Win condition ── */}
        <label>how to win:</label>
        <div className="flex gap-2 mt-1 mb-4">
          <SketchOption
            testId="win-rounds"
            selected={config.winCondition === 'rounds'}
            onClick={() => patch({ winCondition: 'rounds' })}
          >
            {config.totalRounds} rounds
          </SketchOption>
          <SketchOption
            testId="win-score"
            selected={config.winCondition === 'first_to_score'}
            onClick={() => patch({ winCondition: 'first_to_score' })}
          >
            first to {config.targetScore}
          </SketchOption>
        </div>

        {config.winCondition === 'rounds' ? (
          <SketchSlider
            testId="rounds-slider"
            label="rounds"
            min={1}
            max={20}
            step={1}
            value={config.totalRounds}
            onChange={(v) => patch({ totalRounds: v })}
          />
        ) : (
          <SketchSlider
            testId="target-score-slider"
            label="target score"
            min={50}
            max={1000}
            step={50}
            value={config.targetScore}
            onChange={(v) => patch({ targetScore: v })}
          />
        )}

        <Separator />

        <SketchSlider
          testId="turn-timer-slider"
          label="turn timer (seconds)"
          min={10}
          max={120}
          step={5}
          value={config.turnTimeSeconds}
          onChange={(v) => patch({ turnTimeSeconds: v })}
        />

        <div className="h-3" />

        {/* Zero is off rather than instant — nobody wants a scoreboard they
            cannot read, and "off" is what waiting for the host is called. */}
        <SketchSlider
          testId="autostart-slider"
          label="next round starts by itself"
          min={0}
          max={60}
          step={5}
          value={config.autoNextRoundSeconds ?? 0}
          onChange={(v) => patch({ autoNextRoundSeconds: v === 0 ? null : v })}
          format={(v) => (v === 0 ? 'off' : `after ${v}s`)}
        />

        <Separator />

        {/* ── House rules ── */}
        <label>house rules:</label>
        <div className="flex flex-col gap-1.5 mt-1.5">
          {catalog.rules.map((rule) => {
            const active = config.ruleIds.includes(rule.id)
            return (
              <button
                key={rule.id}
                onClick={() => toggleRule(rule.id)}
                data-testid={`rule-${rule.id}`}
                data-active={active}
                className={`flex items-start gap-2.5 text-left bg-transparent border-none cursor-pointer p-1 rounded transition-opacity ${
                  active ? 'opacity-100' : 'opacity-55'
                }`}
              >
                <span
                  className={`mt-1.5 w-2.5 h-2.5 rounded-full shrink-0 ${
                    active ? 'bg-[var(--accent)]' : 'bg-[var(--ink)]/20'
                  }`}
                />
                <span>
                  <span className="display text-lg block leading-tight">{rule.name}</span>
                  <small>{rule.description}</small>
                </span>
              </button>
            )
          })}
        </div>
      </div>

      {inspectedCard && (
        <div
          onClick={() => setInspectedCard(null)}
          className="fixed inset-0 z-[500] flex items-center justify-center cursor-pointer bg-[var(--felt)]/60 backdrop-blur-[16px] animate-[inspectFadeIn_200ms_ease-out]"
        >
          <div className="scale-[2.8] pointer-events-none animate-[inspectCardPop_300ms_cubic-bezier(.2,.9,.3,1.3)_both]">
            <PlayingCard card={inspectedCard} size="deck" />
          </div>
        </div>
      )}
    </>
  )
}
