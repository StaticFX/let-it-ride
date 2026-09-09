import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useCatalog } from '../../state/gameStore'
import type { Card as CardType, DeckConfig, GameConfig, GameMode } from '../../game/types'
import { CUSTOM_DECK_ID, modeOf } from '../../game/types'

/**
 * The deck rolling rules brings with it, and the house rule it always underlays.
 *
 * Named here rather than looked up because the mode chooser has to send both
 * with the mode itself, and a lobby that could only describe the game after the
 * server had corrected it would show the wrong game for one round trip.
 */
const ROLLING_RULES_DECK_ID = 'rollingrules'
const EXTREME_RULE_ID = 'extreme'
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
  // Looked up out here rather than inside `chooseMode`: the guard above narrows
  // `catalog` for this scope but not into a hoisted function body.
  const rollingRulesDeck = catalog.decks.find((d) => d.id === ROLLING_RULES_DECK_ID)?.deck
  const hasGamblerCards = (catalog.gamblers?.length ?? 0) > 0

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

  const mode = modeOf(config)

  /**
   * Switches the game, and brings its deck and its house rules with it.
   *
   * "Extreme" is sent along rather than quietly assumed. The server forces it
   * either way, so this changes nothing about how the game plays — what it
   * changes is that the lobby *shows* it on, which is the difference between a
   * rule the table agreed to and one it discovers in round one.
   */
  function chooseMode(next: GameMode) {
    if (next === 'rollingRules') {
      patch({
        mode: next,
        deckPresetId: ROLLING_RULES_DECK_ID,
        deck: rollingRulesDeck ?? config.deck,
        ruleIds: [...new Set([...config.ruleIds, EXTREME_RULE_ID])],
      })
    } else {
      patch({ mode: next })
    }
  }

  return (
    <>
      <div className="sketch-box rounded p-4 relative">
        <h2 className="mb-3.5 -rotate-1">~ settings ~</h2>

        {/* ── Game mode ──
            At the top because everything under it is conditioned on it. A mode
            is not a house rule: it changes the screens you see rather than a
            number the engine reads, and putting it in that row of small toggles
            would have hidden a whole second hand in a tick box. */}
        {hasGamblerCards && (
          <>
            <label>game mode:</label>
            <div className="flex gap-2 mt-1 mb-2 justify-center">
              <SketchOption
                testId="mode-classic"
                selected={mode === 'classic'}
                onClick={() => chooseMode('classic')}
              >
                let it ride
              </SketchOption>
              <SketchOption
                testId="mode-rollingRules"
                selected={mode === 'rollingRules'}
                onClick={() => chooseMode('rollingRules')}
              >
                rolling rules
              </SketchOption>
            </div>
            <p className="text-muted text-center text-[13px] mb-3 leading-snug italic">
              {mode === 'rollingRules'
                ? 'a second hand nobody else can see, and points you can spend'
                : 'the game as it is'}
            </p>
            <Separator />
          </>
        )}

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
                  className="relative bg-transparent border-none p-0 cursor-pointer transition-transform duration-100 [@media(hover:hover)]:hover:scale-110 [@media(hover:hover)]:hover:-rotate-2"
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

        {mode === 'rollingRules' && (
          <>
            <div className="h-3" />
            {/* A ceiling rather than a schedule: the shop shuts the moment
                everybody says they are finished, and at a table with bots on it
                that is almost at once. This is only how long the last person
                still deciding gets. */}
            <SketchSlider
              testId="shop-timer-slider"
              label="shop timer (seconds)"
              min={15}
              max={180}
              step={15}
              value={config.shopSeconds ?? 120}
              onChange={(v) => patch({ shopSeconds: v })}
            />
          </>
        )}

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
            // A rule the mode brings with it is shown on and cannot be taken
            // off — the server would only put it back, and a toggle that
            // silently undoes itself is worse than one that says why.
            const forced = mode === 'rollingRules' && rule.id === EXTREME_RULE_ID
            return (
              <button
                key={rule.id}
                onClick={forced ? undefined : () => toggleRule(rule.id)}
                data-testid={`rule-${rule.id}`}
                data-active={active || forced}
                data-forced={forced}
                className={`flex items-start gap-2.5 text-left bg-transparent border-none p-1 rounded transition-opacity ${
                  forced ? 'cursor-default' : 'cursor-pointer'
                } ${active || forced ? 'opacity-100' : 'opacity-55'}`}
              >
                <span
                  className={`mt-1.5 w-2.5 h-2.5 rounded-full shrink-0 ${
                    active || forced ? 'bg-[var(--accent)]' : 'bg-[var(--ink)]/20'
                  }`}
                />
                <span>
                  <span className="display text-lg block leading-tight">{rule.name}</span>
                  <small>{forced ? 'always on in rolling rules' : rule.description}</small>
                </span>
              </button>
            )
          })}
        </div>
      </div>

      {/* Sent to the body rather than left where it was written. A full-screen
          overlay nested six levels inside a form is one ancestor `transform`,
          `filter` or `overflow` away from being clipped, or from covering the
          settings page rather than the window. A portal is the only version of
          this that cannot be broken from above. */}
      {inspectedCard && createPortal(
        <div
          onClick={() => setInspectedCard(null)}
          className="fixed inset-0 z-[500] flex items-center justify-center cursor-pointer bg-[var(--felt)]/60 backdrop-blur-[16px] animate-[inspectFadeIn_200ms_ease-out]"
        >
          <div className="scale-[2.8] pointer-events-none animate-[inspectCardPop_300ms_cubic-bezier(.2,.9,.3,1.3)_both]">
            <PlayingCard card={inspectedCard} size="deck" />
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}
