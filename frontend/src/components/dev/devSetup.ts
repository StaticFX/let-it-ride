/**
 * What the testing panel can name, and the situations it can write down.
 *
 * Nothing here decides anything about a game — the same rule the rest of the
 * client plays by. A setup is a description of a table, and the server is what
 * turns it into one. See `DevSetup`.
 */
import { modeOf, type Card, type Catalog, type DeckConfig, type DevSetup, type GameStateView, type Player } from '../../game/types'

/**
 * How the server names a card: by its definition where it has one, by what is
 * printed on it otherwise. A "7" is a seven whichever of the deck's sevens the
 * table happens to hand over; a freeze is the freeze.
 */
export function cardName(card: Card): string {
  return card.defId ?? card.label
}

export interface Palette {
  numbers: Card[]
  actions: Card[]
  passives: Card[]
  gamblers: Card[]
}

/**
 * Every card that can be asked for, as a face to click.
 *
 * The numbers come off the table's own deck, because what is printed on them is
 * the deck's business — a "K" is worth what the classic deck says it is. The
 * action and modifier cards come off the catalog rather than the deck, so a card
 * this table is not playing with can still be dealt: the server mints one when
 * it has none, which is the whole point of being able to ask for it.
 */
export function buildPalette(deck: DeckConfig | undefined, catalog: Catalog): Palette {
  const numbers = [...(deck?.numberCards ?? [])]
    .sort((a, b) => a.value - b.value)
    .map<Card>((entry) => {
      const label = entry.label ?? String(entry.value)
      return { id: `dev-n-${label}`, kind: 'number', label, value: entry.value, suit: entry.suits?.[0] }
    })

  const actions = catalog.actions
    .filter((action) => action.deckable !== false)
    .map<Card>((action) => ({ id: `dev-a-${action.id}`, kind: 'action', label: action.name, value: 0, defId: action.id }))

  const passives = catalog.passives.map<Card>((passive) => ({
    id: `dev-p-${passive.id}`,
    kind: 'passive',
    label: passive.name,
    value: 0,
    defId: passive.id,
  }))

  // Every gambler card, sorted by rarity, whatever mode the table is in: a
  // jackpot is a thing you would otherwise have to be dealt or outbid somebody
  // for, and putting one on top of the deck is a fine way to test the draw.
  const gamblers = [...(catalog.gamblers ?? [])]
    .sort((a, b) => RARITY_ORDER.indexOf(a.rarity) - RARITY_ORDER.indexOf(b.rarity) || a.name.localeCompare(b.name))
    .map<Card>((gambler) => ({
      id: `dev-g-${gambler.id}`,
      kind: 'gambler',
      label: gambler.name,
      value: 0,
      defId: gambler.id,
    }))

  return { numbers, actions, passives, gamblers }
}

const RARITY_ORDER = ['common', 'rare', 'jackpot']

/** The faces this deck prints, in order, one of each. */
export function distinctNumbers(deck: DeckConfig): string[] {
  return [...deck.numberCards]
    .sort((a, b) => a.value - b.value)
    .map((entry) => entry.label ?? String(entry.value))
}

export interface Scenario {
  id: string
  label: string
  hint: string
  setup: DevSetup
}

/**
 * The handful of situations worth one click.
 *
 * Each is written against the table as it stands — this room's flip target, this
 * deck's faces, this player's hand — rather than against the game in general, so
 * "one off the flip" means six cards at a normal table and eight under "flip 9".
 */
export function buildScenarios(state: GameStateView, meId: string | null, catalog?: Catalog | null): Scenario[] {
  const me: Player | undefined = state.players.find((p) => p.id === meId) ?? state.players[0]
  const faces = distinctNumbers(state.config.deck)
  const target = state.flip7Target
  const playing = state.phase === 'PLAYING'
  const scenarios: Scenario[] = []

  if (me && playing && faces.length >= target) {
    const almost = faces.slice(0, target - 1)
    scenarios.push({
      id: 'one-off-the-flip',
      label: 'one off the flip',
      hint: `${target - 1} different numbers in your hand`,
      setup: {
        skipWait: true,
        turnPlayerId: me.id,
        players: [{ playerId: me.id, hand: almost, status: 'active' }],
      },
    })
    scenarios.push({
      id: 'flip-next',
      label: 'flip on your next card',
      hint: 'the hand, and the card that finishes it on top of the deck',
      setup: {
        skipWait: true,
        turnPlayerId: me.id,
        stack: [faces[target - 1]],
        players: [{ playerId: me.id, hand: almost, status: 'active' }],
      },
    })
  }

  if (me && playing && faces.length > 0) {
    // A duplicate of something already held. An action card in hand is no use
    // here — it is the number that busts you.
    const held = me.hand.find((card) => card.kind === 'number')
    scenarios.push({
      id: 'bust-next',
      label: 'bust on your next card',
      hint: 'a card you are already holding, on top of the deck',
      setup: {
        skipWait: true,
        turnPlayerId: me.id,
        stack: [held ? cardName(held) : faces[0]],
        players: held ? [] : [{ playerId: me.id, hand: [faces[0]], status: 'active' }],
      },
    })
    scenarios.push({
      id: 'second-chance',
      label: 'hand yourself a second chance',
      hint: 'so the next duplicate is survivable',
      setup: { skipWait: true, players: [{ playerId: me.id, passives: ['secondLife'] }] },
    })
  }

  if (playing) {
    scenarios.push({
      id: 'end-round',
      label: 'end the round now',
      hint: 'everybody still in goes out, and it scores as it stands',
      setup: { endRound: true, skipWait: true },
    })
  }

  // The mode's own situations. A gambler card is the one thing in the game
  // there is no way at all to play towards: it arrives on a draw you do not
  // control or a shelf you did not roll, so waiting for the deck to agree with
  // you is otherwise the only way to hold the card you want to look at.
  if (me && catalog && modeOf(state.config) === 'rollingRules' && state.phase !== 'LOBBY') {
    const held = (state.myGamblers ?? []).map(cardName)
    const jackpots = (catalog.gamblers ?? []).filter((g) => g.rarity === 'jackpot')
    const limit = state.gamblerLimits?.[me.id] ?? 5

    if (jackpots.length > 0) {
      scenarios.push({
        id: 'hand-me-a-jackpot',
        label: 'hand yourself a jackpot',
        hint: jackpots.map((g) => g.name).join(', '),
        setup: {
          skipWait: true,
          players: [{ playerId: me.id, gamblers: [...held, jackpots[0].id] }],
        },
      })
      scenarios.push({
        id: 'full-tray',
        label: 'a tray with no room left',
        hint: `${limit} cards, so the next one has nowhere to go`,
        setup: {
          skipWait: true,
          players: [
            {
              playerId: me.id,
              // Longest first, then trimmed: the tray fills with whatever the
              // catalog has, and a table that has been dealt some already keeps
              // the ones it is carrying rather than having them swept.
              gamblers: [...held, ...(catalog.gamblers ?? []).map((g) => g.id)].slice(0, limit),
            },
          ],
        },
      })
    }

    const next = (catalog.gamblers ?? [])[0]
    if (next) {
      scenarios.push({
        id: 'gambler-next',
        label: 'a gambler card on your next draw',
        hint: `a ${next.name}, on top of the deck`,
        setup: { skipWait: true, turnPlayerId: me.id, stack: [next.id] },
      })
    }

    // Short of the target on purpose. A flat thousand each ended the game on
    // the spot at any ordinary table, which is a scenario that answers a
    // different question than the one it was reached for.
    const rich =
      state.config.winCondition === 'first_to_score'
        ? Math.max(0, Math.min(1000, state.config.targetScore - 20))
        : 1000
    scenarios.push({
      id: 'rich',
      label: 'everybody rich',
      hint: `${rich} each, so the shop is worth walking into`,
      setup: { players: state.players.map((p) => ({ playerId: p.id, score: rich })) },
    })
  }

  if (state.phase !== 'LOBBY') {
    if (state.config.winCondition === 'first_to_score') {
      scenarios.push({
        id: 'match-point',
        label: 'everybody on match point',
        hint: `ten short of ${state.config.targetScore}`,
        setup: {
          players: state.players.map((p) => ({
            playerId: p.id,
            score: Math.max(0, state.config.targetScore - 10),
          })),
        },
      })
    } else {
      scenarios.push({
        id: 'last-round',
        label: 'make this the last round',
        hint: `round ${state.config.totalRounds} of ${state.config.totalRounds}`,
        setup: { round: state.config.totalRounds },
      })
    }
  }

  return scenarios
}
