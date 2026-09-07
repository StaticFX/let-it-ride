import type { Offer } from '../../game/types'
import { OfferShelf } from './OfferShelf'

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
export function Shop({ offers, purse, chosen, waiting, onPick, x, y, testId = 'shop' }: {
  offers: Offer[]
  purse: number
  /** The id already picked, while the answer is on its way to the server. */
  chosen: string | null
  /** The table is animating, so the pick is held rather than sent. */
  waiting: boolean
  onPick: (offerId: string) => void
  x: number
  y: number
  /**
   * Stable handle for the end-to-end suite. The sheet has an id because it is
   * the only shop today; a second one somewhere else has to be tellable from
   * this one, while the offers inside it keep their own ids either way so
   * "click an offer" stays one thing a spec knows how to do.
   */
  testId?: string
}) {
  return (
    <div
      className="fixed z-[240] -translate-x-1/2 shop-sheet"
      style={{ left: x, top: y }}
      data-testid={testId}
      data-offers={offers.length}
      data-chosen={chosen ?? ''}
    >
      <div className="sketch-box rounded px-5 py-4 flex flex-col items-center gap-3 max-w-[92vw]">
        <div className="display text-xl -rotate-1">
          buy a card — <span className="text-[var(--accent)]">{purse}</span> to spend
        </div>

        <OfferShelf
          offers={offers}
          purse={purse}
          chosen={chosen}
          onPick={onPick}
          className="max-w-[560px] max-h-[46vh] overflow-y-auto"
        />

        {chosen && <small>{waiting ? 'when the table settles…' : 'bought!'}</small>}
      </div>
    </div>
  )
}
