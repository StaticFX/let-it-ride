/**
 * Reading and writing a deck, without drawing any of it.
 *
 * None of this decides anything: the server sanitises whatever a client sends
 * and its answer is the one a table plays. What is here is so the builder can
 * say the same thing the server would, and say it while you are still typing.
 */
import type { Card, Catalog, DeckConfig } from './types'

export function numberCount(deck: DeckConfig): number {
  return deck.numberCards.reduce((total, entry) => total + entry.count, 0)
}

export function deckSize(deck: DeckConfig): number {
  return numberCount(deck) + deck.actionCards.length + deck.passiveCards.length
}

export function countOfNumber(deck: DeckConfig, value: number): number {
  return deck.numberCards.find((entry) => entry.value === value)?.count ?? 0
}

export function countOfId(ids: string[], id: string): number {
  return ids.filter((each) => each === id).length
}

/** The same list with exactly `count` copies of `id` in it. */
export function withCount(ids: string[], id: string, count: number): string[] {
  return [...ids.filter((each) => each !== id), ...Array.from({ length: count }, () => id)]
}

export function withNumber(deck: DeckConfig, value: number, count: number): DeckConfig {
  const rest = deck.numberCards.filter((entry) => entry.value !== value)
  const entry = count > 0 ? [{ value, count, label: String(value) }] : []
  return { ...deck, numberCards: [...rest, ...entry].sort((a, b) => a.value - b.value) }
}

/**
 * Why a table would refuse this deck, or null when it would take it.
 *
 * The limits come off the catalog rather than being restated here, so there is
 * one set of them and the builder cannot drift away from what the server will
 * actually accept.
 */
export function deckProblem(deck: DeckConfig, catalog: Catalog): string | null {
  const limits = catalog.deckLimits
  if (!limits) return null
  const numbers = numberCount(deck)
  const total = deckSize(deck)
  if (numbers < limits.minNumberCards) {
    return `needs at least ${limits.minNumberCards} number cards — it has ${numbers}`
  }
  if (total > limits.maxCards) {
    return `too many cards to shuffle — ${total} of a possible ${limits.maxCards}`
  }
  if (numbers < total * limits.minNumberShare) {
    return 'too many action cards for the numbers to keep up with'
  }
  return null
}

/**
 * A deck listed the way a preset lists itself — a face and a count per card —
 * so anything that can show a preset's contents can show a built deck's too.
 */
export function describeDeck(deck: DeckConfig, catalog: Catalog): { card: Card; count: number }[] {
  const rows: { card: Card; count: number }[] = []

  for (const entry of deck.numberCards) {
    const label = entry.label ?? String(entry.value)
    rows.push({
      card: { id: `built-n-${label}`, kind: 'number', label, value: entry.value, suit: entry.suits?.[0] },
      count: entry.count,
    })
  }

  const tally = (ids: string[]) =>
    ids.reduce<Record<string, number>>((counts, id) => ({ ...counts, [id]: (counts[id] ?? 0) + 1 }), {})

  for (const [id, count] of Object.entries(tally(deck.actionCards))) {
    const def = catalog.actions.find((a) => a.id === id)
    if (!def) continue
    rows.push({ card: { id: `built-a-${id}`, kind: 'action', label: def.name, value: 0, defId: id }, count })
  }
  for (const [id, count] of Object.entries(tally(deck.passiveCards))) {
    const def = catalog.passives.find((p) => p.id === id)
    if (!def) continue
    rows.push({ card: { id: `built-p-${id}`, kind: 'passive', label: def.name, value: 0, defId: id }, count })
  }

  return rows
}

/** A deck as something you can send somebody. */
export function encodeDeck(deck: DeckConfig): string {
  return btoa(JSON.stringify(deck))
}

/** ...and back, or null when it was not one. The server checks it regardless. */
export function decodeDeck(text: string): DeckConfig | null {
  try {
    const parsed = JSON.parse(atob(text.trim())) as DeckConfig
    if (!Array.isArray(parsed?.numberCards)) return null
    return {
      numberCards: parsed.numberCards,
      actionCards: parsed.actionCards ?? [],
      passiveCards: parsed.passiveCards ?? [],
    }
  } catch {
    return null
  }
}
