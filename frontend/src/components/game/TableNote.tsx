import type { GameConfig } from '../../game/types'
import { useCatalog } from '../../state/gameStore'
import { useElementSize } from '../../hooks/useElementSize'
import { RoughBox, RoughSquiggle } from '../ui/RoughShapes'

const WIDTH = 214

/**
 * What the room settled on before the deal — which deck is being played and
 * which house rules are live. Everything here is decided in the lobby and then
 * never mentioned again, which is exactly why it is worth keeping on the table.
 */
export function TableNote({ config }: { config: GameConfig }) {
  const catalog = useCatalog()
  const { ref, size } = useElementSize<HTMLDivElement>()

  // Not `findDeck` — its fallback to the first preset would confidently name
  // the wrong deck. A deck this client has no entry for keeps its own id.
  const deck = catalog?.decks.find((d) => d.id === config.deckPresetId)
  const rules = catalog?.rules.filter((r) => config.ruleIds.includes(r.id)) ?? []
  const cardCount =
    config.deck.numberCards.reduce((total, entry) => total + entry.count, 0) +
    config.deck.actionCards.length +
    config.deck.passiveCards.length

  return (
    <div
      className="relative rotate-[1.4deg]"
      data-testid="table-note"
      data-deck={config.deckPresetId}
      data-rules={config.ruleIds.join(',')}
    >
      {/* A strip of tape holding it down */}
      <div className="absolute -top-3 left-1/2 z-[2] h-6 w-[74px] -translate-x-1/2 -rotate-[3.5deg] border-x-2 border-[var(--ink)]/10 bg-[var(--card-face)]/70 shadow-[0_1px_2px_rgba(0,0,0,0.05)]" />

      <div
        ref={ref}
        className="relative rounded-[2px] bg-[var(--card-face)] px-4 pt-3 pb-3.5 shadow-[4px_5px_0_0_var(--ink)]"
        style={{ width: WIDTH }}
      >
        {size.h > 0 && (
          <RoughBox width={size.w} height={size.h} stroke="var(--ink)" strokeWidth={1.8} roughness={2.2} boil={false} />
        )}

        <div className="relative z-[1]">
          <div className="display -rotate-[0.6deg] text-lg leading-none">the table</div>
          <div className="relative mb-1 h-2">
            <RoughSquiggle
              width={WIDTH - 32}
              height={8}
              stroke="var(--ink)"
              strokeWidth={1}
              amplitude={1.1}
              segments={8}
              roughness={1.5}
              boil={false}
            />
          </div>

          <label className="text-xs">deck</label>
          <div className="mb-2 flex items-baseline gap-1.5">
            <span className="display text-[17px] leading-tight" data-testid="table-note-deck">
              {deck?.name ?? config.deckPresetId}
            </span>
            <small className="number text-[15px] leading-none">{cardCount}</small>
          </div>

          <label className="block text-xs">house rules</label>
          {rules.length === 0 ? (
            <small className="italic">none — straight rules</small>
          ) : (
            <ul className="m-0 mt-0.5 flex list-none flex-col gap-0.5 p-0">
              {rules.map((rule) => (
                <li
                  key={rule.id}
                  title={rule.description}
                  data-testid="table-note-rule"
                  className="flex items-start gap-1.5 leading-tight"
                >
                  <span className="mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--accent)]" />
                  <span className="text-[15px]">{rule.name}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}
