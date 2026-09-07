import { useState } from 'react'
import type { Card as CardType } from '../../game/types'
import { useGameStore } from '../../state/gameStore'
import { send } from '../../net/client'
import { useCountdown } from '../../hooks/useCountdown'
import { OfferShelf } from '../overlays/OfferShelf'
import { Scoreboard } from '../game/Scoreboard'
import { SketchButton } from '../ui/Button'
import { PlayingCard } from '../cards/PlayingCard'
import { Showdown } from '../overlays/Showdown'

/**
 * The window between rounds: a shelf of cards, and your score to spend on them.
 *
 * A screen rather than a section of the scoreboard or an overlay. The summary is
 * a *reading* screen with nothing being asked of you; this is a doing one with a
 * hard deadline, a purse being spent and a decision at the end of it, and
 * bolting it under the summary would put the clock below the fold on a short
 * window. An overlay would say the thing behind it is still live, and between
 * rounds nothing is.
 *
 * The scoreboard is here — not as decoration, but because the money *is* the
 * score. Spending two hundred on a jackpot moves the bar you are trying to fill,
 * and you should be able to watch it move.
 */
export function Interlude() {
  const state = useGameStore((s) => s.state)
  const localPlayerId = useGameStore((s) => s.localPlayerId)
  const shop = state?.interlude
  const seconds = useCountdown(state?.interludeUntil)
  // Held here rather than sent on every keystroke: a sealed bid goes over once,
  // with "i'm done", and a bid the server knew about early would not be sealed.
  const [bid, setBid] = useState(0)
  // Picking a card up to read it. The shelf is where every one of these faces is
  // met for the first time, and buying one to find out what it does is a poor
  // way to learn — so looking is its own act here and costs nothing.
  const [inspected, setInspected] = useState<CardType | null>(null)

  if (!state || !shop) return null

  const me = state.players.find((p) => p.id === localPlayerId)
  const purse = me?.score ?? 0
  const spent = (shop.openingScore ?? purse) - purse
  const offers = shop.offers ?? []
  const bought = shop.bought ?? []
  const done = shop.done ?? []
  const ready = !!localPlayerId && done.includes(localPlayerId)
  // Who the window is still waiting on. Names are not sent for who has *not*
  // finished, only for who has — everybody else is the difference.
  const waiting = state.players.filter((p) => !done.includes(p.id)).length
  const slotsFree = shop.slotsFree ?? 0

  return (
    <div
      className="page-shell justify-start pt-6 pb-8 overflow-y-auto"
      data-testid="interlude"
      data-seconds={seconds ?? ''}
      data-purse={purse}
      data-ready={ready}
      data-slots-free={slotsFree}
    >
      {/* Not `content-width`. That caps a page at 60% of a wide screen, which is
          right for a column of prose and wrong for a shelf of cards you are
          meant to compare at a glance — it left four cards crammed into the
          middle third with the description at nine points and the rest of the
          monitor empty. */}
      <div className="w-full max-w-[1500px] px-4 sm:px-8 flex flex-col items-center gap-4 my-auto">
        <div className="flex items-baseline gap-4">
          <h2 className="-rotate-1 text-4xl sm:text-5xl">~ the table is open ~</h2>
          {seconds !== null && (
            <span
              className={`number text-4xl sm:text-5xl leading-none ${seconds <= 10 ? 'text-[var(--accent)]' : ''}`}
              data-testid="interlude-clock"
              data-seconds={seconds}
            >
              {seconds}
            </span>
          )}
        </div>

        {/* Shelf and lot side by side once there is room for both. Stacked, the
            auction sat below the fold on a two-minute clock, which is the one
            place in the game where not scrolling costs you money. */}
        <div className="w-full flex flex-col xl:flex-row items-stretch justify-center gap-4">
          <div className="sketch-box rounded p-5 sm:p-6 flex-1 flex flex-col items-center gap-4">
            <div className="display text-2xl sm:text-3xl -rotate-1">
              buy a card — <span className="text-[var(--accent)]">{purse}</span> to spend
              {spent > 0 && <small className="ml-2 text-base">({spent} spent)</small>}
            </div>

            {offers.length === 0 ? (
              <p className="text-muted">nothing on the shelf tonight</p>
            ) : (
              <OfferShelf
                offers={offers}
                purse={purse}
                slotsFree={slotsFree}
                bought={bought}
                onPick={(offerId) => send({ type: 'BUY', offerId })}
                onInspect={setInspected}
                size="large"
              />
            )}

            <small className="text-[var(--ink-soft)] text-base">
              {slotsFree <= 0
                ? 'no room — you are carrying all you can'
                : `${slotsFree} ${slotsFree === 1 ? 'slot' : 'slots'} left in your hand`}
              {' · tap a card to read it'}
            </small>
          </div>

          {/* The auction. One jackpot, one sealed bid each, and the bid goes over
              with "i'm done" rather than on its own — a bid you could still place
              after the shop shut would be a bid against a purse you had already
              spent. */}
          {shop.lot && !shop.sale && (
            <div
              className="sketch-box rounded p-5 sm:p-6 flex flex-col items-center justify-center gap-3 xl:w-[340px] shrink-0"
              data-testid="auction"
              data-lot={shop.lot.card.defId}
              data-my-bid={shop.myBid ?? ''}
              data-revealed="false"
            >
              <div className="display text-2xl sm:text-3xl -rotate-1">under the hammer</div>
              <button
                onClick={() => setInspected(shop.lot!.card)}
                data-testid="auction-inspect"
                className="bg-transparent border-none p-0 cursor-pointer transition-transform duration-150 hover:scale-105 hover:-rotate-1"
              >
                <PlayingCard card={shop.lot.card} size="large" />
              </button>
              <small className="text-[var(--ink-soft)] text-base text-center">
                worth about {shop.lot.price}. highest sealed bid takes it
              </small>
              {ready ? (
                <div className="display text-xl">
                  {bid > 0 ? `you bid ${bid}` : 'you did not bid'}
                </div>
              ) : (
                <input
                  type="number"
                  min={0}
                  max={purse}
                  value={bid || ''}
                  onChange={(event) => setBid(Math.max(0, Number(event.target.value) || 0))}
                  placeholder="your bid"
                  data-testid="bid-input"
                  className="sketch-input number text-3xl text-center w-40"
                />
              )}
            </div>
          )}
        </div>

        {/* ...and how it came out, once the hammer has fallen. */}
        {shop.sale && (
          <Showdown
            title={shop.sale.winnerId ? 'sold!' : 'no takers'}
            sides={Object.entries(shop.sale.bids ?? {}).map(([playerId, points]) => ({
              name: state.players.find((p) => p.id === playerId)?.name ?? 'somebody',
              label: String(points),
              lost: playerId !== shop.sale?.winnerId,
            }))}
            footnote={
              shop.sale.winnerId
                ? `${state.players.find((p) => p.id === shop.sale?.winnerId)?.name ?? 'somebody'} takes it for ${shop.sale.price}`
                : 'nobody wanted it'
            }
          />
        )}

        <SketchButton
          variant="primary"
          testId="interlude-ready"
          onClick={() => send({ type: 'SHOP_DONE', bid: bid > 0 ? bid : undefined })}
          disabled={ready}
        >
          {ready ? `waiting on ${waiting}…` : "i'm done"}
        </SketchButton>

        {/* Picked up to be read. The same overlay the table uses, so a card is
            looked at the same way wherever you meet one. */}
        {inspected && (
          <div onClick={() => setInspected(null)} className="card-inspect-overlay" data-testid="card-inspect">
            <div className="card-inspect-pop">
              <PlayingCard card={inspected} size="deck" />
            </div>
          </div>
        )}

        {/* Your points, where you can see what you are spending them out of. */}
        <div className="w-full flex justify-center">
          <Scoreboard
            players={state.players}
            currentPlayerId=""
            localPlayerId={localPlayerId}
            worth={() => 0}
            total={(player) => player.score}
            target={state.config.winCondition === 'first_to_score' ? state.config.targetScore : undefined}
          />
        </div>
      </div>
    </div>
  )
}
