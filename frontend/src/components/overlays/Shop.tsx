import type { Offer } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * The deck, laid out with a price on every card.
 *
 * Everything here is already affordable and already in this table's deck — the
 * server worked both out before it asked — so the sheet has no rules of its
 * own to enforce. It shows what is on sale and takes the click.
 *
 * The purse is what the round has left the buyer, not their score: a second
 * purchase has to come out of what the first one left behind.
 */
export function Shop({ offers, purse, chosen, waiting, onPick, x, y }: {
  offers: Offer[]
  purse: number
  /** The id already picked, while the answer is on its way to the server. */
  chosen: string | null
  /** The table is animating, so the pick is held rather than sent. */
  waiting: boolean
  onPick: (offerId: string) => void
  x: number
  y: number
}) {
  return (
    <div
      className="fixed z-[240] -translate-x-1/2 shop-sheet"
      style={{ left: x, top: y }}
      data-testid="shop"
      data-offers={offers.length}
      data-chosen={chosen ?? ''}
    >
      <div className="sketch-box rounded px-5 py-4 flex flex-col items-center gap-3 max-w-[92vw]">
        <div className="display text-xl -rotate-1">
          buy a card — <span className="text-[var(--accent)]">{purse}</span> to spend
        </div>

        <div className="flex flex-wrap justify-center gap-2.5 max-w-[560px] max-h-[46vh] overflow-y-auto py-1">
          {offers.map((offer) => {
            const picked = chosen === offer.id
            const spent = !!chosen && !picked
            return (
              <button
                key={offer.id}
                onClick={() => !chosen && onPick(offer.id)}
                disabled={!!chosen}
                data-testid="shop-offer"
                data-offer-id={offer.id}
                data-price={offer.price}
                data-picked={picked}
                className={`relative bg-transparent border-none p-0 transition-transform duration-150 ${
                  chosen ? 'cursor-default' : 'cursor-pointer hover:scale-110 hover:-rotate-2'
                } ${picked ? 'scale-110 -rotate-2' : ''} ${spent ? 'opacity-30' : ''}`}
              >
                <PlayingCard card={offer.card} size="small" glowing={picked} />
                <span className="absolute -bottom-1 -right-1 z-10 display text-[11px] text-[var(--card-face)] bg-[var(--ink)] rounded-full px-1.5 leading-[15px]">
                  {offer.price}
                </span>
              </button>
            )
          })}
        </div>

        {chosen && <small>{waiting ? 'when the table settles…' : 'bought!'}</small>}
      </div>
    </div>
  )
}
