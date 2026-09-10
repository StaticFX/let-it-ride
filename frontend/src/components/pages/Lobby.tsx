import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore, findRule } from '../../state/gameStore'
import { useAuthStore } from '../../state/authStore'
import { connect, createRoom, leaveGame, lookupRoom, send } from '../../net/client'
import { loginHref, takeAuthError } from '../../net/auth'
import { Stats } from './Stats'
import type { GameConfig } from '../../game/types'
import { modeOf } from '../../game/types'
import { CardBack } from '../cards/CardBack'
import { PlayingCard } from '../cards/PlayingCard'
import { SketchButton } from '../ui/Button'
import { LobbyConfig } from './LobbyConfig'
import { deckSize, describeDeck } from '../../game/deck'
import { RulesPage } from '../rules/RulesPage'
import { Countdown } from '../overlays/Countdown'
import { SoundToggle } from '../ui/SoundToggle'
import { SketchInput } from '../ui/SketchInput'
import { TitleMark } from '../ui/TitleMark'
import { PosterBurst } from '../ui/PosterBurst'

const NAME_KEY = 'let-it-ride:name'
const DEFAULT_BOTS = 3
/** What an invite link carries. Query rather than path — see [inviteUrl]. */
const INVITE_PARAM = 'room'

type Screen = 'choose' | 'join' | 'room' | 'settings' | 'stats'

/**
 * `crypto.randomUUID` only exists on secure origins, and a homelab box on plain
 * http over a LAN address is not one — so do not depend on it.
 */
function newPlayerId(): string {
  const bytes = new Uint8Array(8)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * Gets something to somebody else, by whichever of the three doors is open.
 *
 * The clipboard is only offered on a secure origin, and a homelab box on plain
 * http over a LAN address is not one — which is the documented way this thing
 * gets played. So a phone, where the tap that copies it is the whole point, was
 * the case where nothing happened at all and the caption underneath went on
 * cheerfully saying "share it with your friends".
 *
 * The share sheet is the phone's own answer to this and needs no secure
 * context; the selection is the last resort, and is what the `select-all` on
 * the code itself has always been for — and, for the link, what the anchor is
 * for, because a browser will always hand you the address of an `<a>` even when
 * it will not let a script near the clipboard. What comes back says which of
 * them happened, because "copied!" is a lie if the sheet was cancelled.
 */
async function handOut(text: string, sheet: ShareData): Promise<'copied' | 'shared' | 'none'> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return 'copied'
    }
  } catch {
    // Falls through.
  }
  try {
    if (navigator.share) {
      await navigator.share(sheet)
      return 'shared'
    }
  } catch {
    // Cancelling the sheet lands here, and cancelling is an answer.
  }
  return 'none'
}

/**
 * The address that arrives at a table, which is the room code with a door in
 * front of it.
 *
 * A query parameter and not a path segment. `/WXYZ` is the prettier link, but
 * it only resolves because the static handler falls everything it does not
 * recognise through to the SPA shell — so it is a link whose correctness lives
 * in the server's routing and in whatever proxy somebody has put in front of
 * it. `?room=WXYZ` is the same page either way, and the same page under the
 * Vite dev server, which is the one thing a link you are asking a stranger to
 * click should be.
 *
 * The rest of the address bar is dropped rather than carried: an invite is
 * where the game is, not where the host happened to be standing.
 */
function inviteUrl(code: string): string {
  const url = new URL(window.location.href)
  url.search = ''
  url.hash = ''
  url.searchParams.set(INVITE_PARAM, code)
  return url.toString()
}

/**
 * Reads the code somebody arrived with, and takes it back out of the address
 * bar as it does.
 *
 * At module load rather than in a state initialiser, because this has a side
 * effect and StrictMode calls an initialiser twice. Once is what it must be:
 * the parameter is removed as it is read, so the bar stops advertising a table
 * the moment the code has been handed over. Left there it would be a bookmark
 * that works for ten minutes, and a reload after the game had moved on would
 * drop somebody back on a join screen for a room that no longer exists.
 */
function takeInviteCode(): string {
  const url = new URL(window.location.href)
  const code = (url.searchParams.get(INVITE_PARAM) ?? '').trim().toUpperCase().slice(0, 4)
  if (!code) return ''
  url.searchParams.delete(INVITE_PARAM)
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`)
  return code
}

/**
 * The code somebody arrived under — spent, and not merely read.
 *
 * The lobby is unmounted for the length of a game, so a value that survived one
 * would hand a player who came in by link and then walked out the join screen,
 * with the code of the table they had just left already in it, instead of the
 * front door. [Lobby] clears it the moment a seat is taken.
 */
let invitedTo = takeInviteCode()

/**
 * What a sign-in that went wrong came back saying, read once and taken out of
 * the address bar as it goes.
 *
 * Same reasoning as the invite code above, and the same shape: this is a
 * message about one attempt, and a reload should not turn it into a message
 * about nothing. Read at module load rather than in a state initialiser
 * because it has a side effect and StrictMode runs an initialiser twice.
 */
let arrivedWithAuthError = takeAuthError()

export function Lobby() {
  const state = useGameStore((s) => s.state)
  const isHost = useGameStore((s) => s.isHost)
  const roomCode = useGameStore((s) => s.roomCode)
  const connection = useGameStore((s) => s.connection)
  const error = useGameStore((s) => s.error)
  const catalog = useGameStore((s) => s.catalog)

  // A link goes straight to the join screen with its code already in the box;
  // the name is the only thing left to fill in, which is why that screen asks
  // for one. Nothing is joined automatically — walking in under a name you
  // cannot see is not an entrance anybody asked for.
  const [screen, setScreen] = useState<Screen>(invitedTo ? 'join' : 'choose')
  const [typedName, setTypedName] = useState(() => localStorage.getItem(NAME_KEY) ?? '')
  const [joinCode, setJoinCode] = useState(invitedTo)
  const [shared, setShared] = useState<'code' | 'link' | null>(null)
  const [showRules, setShowRules] = useState(false)
  const [showDeckCards, setShowDeckCards] = useState(false)
  const [countdown, setCountdown] = useState(false)
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(state ? null : arrivedWithAuthError)
  const botsWanted = useRef(0)

  const authEnabled = useAuthStore((s) => s.enabled)
  const provider = useAuthStore((s) => s.provider)
  const account = useAuthStore((s) => s.account)

  /**
   * The name that actually goes on the seat.
   *
   * An account's, when there is one, and not editable — which is the whole of
   * what signing in buys at the felt: the name in front of you is one somebody
   * proved. The server enforces exactly this and ignores whatever the socket
   * sends, so this is the client agreeing with the server rather than the
   * client deciding; a tab that lied about it would simply be renamed on
   * arrival.
   *
   * The typed name is left alone underneath rather than overwritten. Signing
   * out puts a guest back where they were instead of on an empty field.
   */
  const playerName = account?.name ?? typedName

  const players = state?.players ?? []
  const config = state?.config
  // Undefined for a deck somebody built. Deliberately not falling back to the
  // first preset: a table playing its own deck would otherwise be described by
  // one it is not playing, down to the card list.
  const preset = catalog?.decks.find((d) => d.id === config?.deckPresetId)
  // ...so a deck with no preset behind it has to describe itself from what the
  // config actually holds.
  const builtCardCount = config ? deckSize(config.deck) : 0
  const builtContents = useMemo(
    () => (preset || !config || !catalog ? [] : describeDeck(config.deck, catalog)),
    [preset, config, catalog],
  )

  // Which screen actually shows is a function of the connection: in a session
  // you are in the room (or its settings), out of one you are at the front
  // door — so leaving from anywhere, including the in-game menu, lands right.
  const inSession = connection === 'connected' || connection === 'connecting'
  const view: Screen = inSession
    ? screen === 'settings' ? 'settings' : 'room'
    : screen === 'stats' ? 'stats' : screen === 'join' ? 'join' : 'choose'

  // Leaving lands on the front door however you go: the pause menu and the
  // disconnect overlay both call `leaveGame()` without going through `leave()`,
  // and somebody who came in by code would otherwise be handed back the join
  // screen by a button that said "back to menu".
  // Spent on the way in, like the invite code. The lobby is unmounted for the
  // length of a game, and a message about one sign-in attempt must not be
  // waiting on the front door when somebody walks back out of a table.
  useEffect(() => {
    arrivedWithAuthError = null
  }, [])

  const wasInSession = useRef(false)
  useEffect(() => {
    if (inSession) {
      wasInSession.current = true
      // Sitting down anywhere spends the invite — see [invitedTo].
      invitedTo = ''
    } else if (wasInSession.current) {
      wasInSession.current = false
      setScreen('choose')
    }
  }, [inSession])

  // Filling a bot table is two steps: host the room, then ask for bots — one
  // per state update, so the server confirms each seat before the next.
  useEffect(() => {
    if (botsWanted.current <= 0 || connection !== 'connected' || !isHost) return
    botsWanted.current -= 1
    send({ type: 'ADD_BOT' })
  }, [connection, isHost, players.length])

  const rememberName = useCallback((name: string) => {
    setTypedName(name)
    localStorage.setItem(NAME_KEY, name)
  }, [])

  async function host(bots = 0) {
    const name = playerName.trim()
    if (!name || busy) return
    setBusy(true)
    setLocalError(null)
    try {
      botsWanted.current = bots
      const room = await createRoom(name)
      connect(room.roomCode, room.playerId, name)
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'could not open a table')
    } finally {
      setBusy(false)
    }
  }

  async function join() {
    const name = playerName.trim()
    const code = joinCode.trim().toUpperCase()
    if (!name || code.length < 4 || busy) return
    setBusy(true)
    setLocalError(null)
    try {
      const info = await lookupRoom(code)
      if (!info.joinable) {
        setLocalError('that game is full or already underway')
        return
      }
      connect(code, newPlayerId(), name)
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'no game with that code')
    } finally {
      setBusy(false)
    }
  }

  function updateConfig(next: GameConfig) {
    send({ type: 'SET_CONFIG', config: next })
  }

  function leave() {
    botsWanted.current = 0
    leaveGame()
    setScreen('choose')
    setLocalError(null)
  }

  const startGame = useCallback(() => {
    setCountdown(false)
    send({ type: 'START_GAME' })
  }, [])

  // ── The record ──
  // Out of session only, like the front door itself. It reads from its own
  // store and its own routes and knows nothing about a table, so it needs
  // nothing from here but the way back.
  if (view === 'stats') {
    return <Stats onClose={() => setScreen('choose')} />
  }

  // Once there is a room, its own flip target answers for the house rules the
  // host has switched on; before that there is only the catalog's default.
  if (showRules) {
    return <RulesPage onClose={() => setShowRules(false)} config={config} flip7Target={state?.flip7Target} />
  }

  // ── Settings ──
  if (view === 'settings' && config) {
    return (
      <div className="page-shell justify-start" data-testid="settings-screen" data-host={isHost}>
        <PosterBurst />
        <div className="poster-content content-width">
          <div className="mb-2 flex justify-center">
            <TitleMark big="settings" scale={0.46} />
          </div>
          <p className="text-muted text-center mb-6">
            {isHost ? 'pick your deck, win condition & house rules' : 'the host decides these'}
          </p>
          <div className={`mb-6 ${isHost ? '' : 'pointer-events-none opacity-70'}`}>
            <LobbyConfig config={config} onChange={updateConfig} />
          </div>
          <SketchButton variant="ghost" testId="settings-done" onClick={() => setScreen('room')}>← done</SketchButton>
        </div>
      </div>
    )
  }

  // ── Start ──
  if (view === 'choose' && connection !== 'connected' && connection !== 'connecting') {
    return (
      <div className="page-shell justify-center" data-testid="title-screen">
        <PosterBurst />

        {/* The deck, at the size the game actually thinks about it. Scenery, and
            the first thing on any of these screens that is not a control — see
            `.title-prop`, which also says why each card needs a wrapper of its
            own to be turned by. */}
        <div className="title-prop" aria-hidden="true">
          <div style={{ transform: 'rotate(-11deg)', opacity: 0.5 }}><CardBack size="deck" /></div>
          <div style={{ transform: 'rotate(0deg)', opacity: 0.75 }}><CardBack size="deck" /></div>
          <div style={{ transform: 'rotate(11deg)' }}><CardBack size="deck" /></div>
        </div>

        <div className="poster-content max-w-[400px] w-full text-center">
          <div className="mb-7 flex justify-center">
            <TitleMark small="let it" big="ride" scale={1.3} />
          </div>

          {/* A name you typed, or a name somebody proved. The signed-in case
              is not the input with `disabled` on it: a greyed-out field asks
              to be edited and then refuses, when what is actually true is
              that this is no longer a question. */}
          {account ? (
            <div className="text-left mb-6" data-testid="signed-in-as">
              <label>you're signed in as</label>
              <div className="sketch-box rounded mt-1 flex items-center justify-between px-3 py-2">
                <span className="display text-2xl truncate">{account.name}</span>
                <small className="text-muted shrink-0 ml-2">{provider}</small>
              </div>
            </div>
          ) : (
            <div className="text-left mb-6">
              <label>what's your name?</label>
              <SketchInput
                type="text"
                data-testid="name-input"
                value={typedName}
                onChange={(e) => rememberName(e.target.value)}
                placeholder="scribble it here…"
                maxLength={16}
                className="mt-1"
              />
            </div>
          )}

          {(localError || error) && <p className="text-[var(--accent)] mb-4" data-testid="lobby-error">{localError ?? error}</p>}

          {/* One column rather than two rows with an "or" ruled between them.
              Four ways in is a menu, and a menu is a thing you read down — the
              old pairing said these were two kinds of choice when they are
              four of the same kind, and it put the one most people want
              (bots, no friends needed) below a divider that read as a footnote. */}
          <div className="menu-stack">
            <SketchButton block variant="primary" testId="host-game" onClick={() => host()} disabled={!playerName.trim() || busy}>
              host a game
            </SketchButton>
            <SketchButton block variant="secondary" testId="join-game" onClick={() => setScreen('join')} disabled={!playerName.trim()}>
              join a game
            </SketchButton>
            <SketchButton block variant="tertiary" testId="play-vs-bots" onClick={() => host(DEFAULT_BOTS)} disabled={!playerName.trim() || busy}>
              play vs bots
            </SketchButton>
            <SketchButton block variant="ghost" testId="open-rules" onClick={() => setShowRules(true)}>rules</SketchButton>
          </div>

          {/* Underneath the menu and not in it. Four ways into a game is the
              menu; signing in is not a fifth way in — it is a thing you can do
              about the four, and a table that keeps no records must not have a
              gap where it would be. */}
          {authEnabled && (
            <div className="mt-5 flex justify-center gap-3.5">
              {account ? (
                <SketchButton variant="ghost" testId="open-stats" onClick={() => setScreen('stats')}>
                  my record
                </SketchButton>
              ) : (
                <a href={loginHref()} data-testid="sign-in">
                  <SketchButton variant="ghost">sign in with {provider}</SketchButton>
                </a>
              )}
            </div>
          )}

          {/* Framed, like the little square buttons along the bottom of an
              arcade menu. Loose on the page it was the one glyph on the screen
              nobody had drawn — a colour emoji floating under four hand-inked
              boxes reads as something the browser put there. */}
          <div className="mt-7 flex justify-center">
            <div className="sketch-box rounded -rotate-1 px-2 py-1">
              <SoundToggle />
            </div>
          </div>
        </div>
      </div>
    )
  }

  // ── Join code ──
  if (view === 'join' && connection !== 'connected' && connection !== 'connecting') {
    return (
      <div className="page-shell justify-center" data-testid="join-screen">
        <PosterBurst />
        <div className="poster-content max-w-[400px] w-full text-center">
          <div className="mb-3 flex justify-center">
            <TitleMark small="join a" big="game" scale={0.62} />
          </div>
          {/* Derived rather than remembered, so it can only ever say what is
              true: the box still holds the code that was in the link. Clear it
              and type another and this is a screen you came to the ordinary
              way again, which is exactly what it goes back to saying. */}
          <p className="text-muted mb-6">
            {invitedTo && joinCode === invitedTo
              ? "you've been invited — just say who you are"
              : 'ask the host for the code'}
          </p>

          {/* The name is asked for again here, and not only carried over from
              the title card, because a link is a way into this screen that
              never went past it: somebody following one has typed nothing
              anywhere. It is the same field and the same key, so anyone who did
              come the long way finds their name already in it — and somebody
              signed in is not asked at all, here for the same reason as there. */}
          {account ? (
            <div className="text-left mb-4" data-testid="signed-in-as">
              <label>you're signed in as</label>
              <div className="sketch-box rounded mt-1 flex items-center justify-between px-3 py-2">
                <span className="display text-2xl truncate">{account.name}</span>
                <small className="text-muted shrink-0 ml-2">{provider}</small>
              </div>
            </div>
          ) : (
            <div className="text-left mb-4">
              <label>what's your name?</label>
              <SketchInput
                type="text"
                data-testid="name-input"
                value={typedName}
                onChange={(e) => rememberName(e.target.value)}
                placeholder="scribble it here…"
                maxLength={16}
                className="mt-1"
              />
            </div>
          )}

          <div className="text-left mb-4">
            <label>room code</label>
            <SketchInput
              type="text"
              data-testid="join-code-input"
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              onKeyDown={(e) => e.key === 'Enter' && join()}
              placeholder="_ _ _ _"
              maxLength={4}
              className="text-center text-4xl tracking-[0.5em] font-bold mt-1"
            />
          </div>

          {(localError || error) && <p className="text-[var(--accent)] mb-4" data-testid="lobby-error">{localError ?? error}</p>}
          <div className="flex justify-center gap-3.5">
            <SketchButton variant="ghost" testId="join-back" onClick={() => { setScreen('choose'); setLocalError(null) }}>← back</SketchButton>
            <SketchButton
              variant="primary"
              testId="join-submit"
              onClick={join}
              disabled={busy || !playerName.trim() || joinCode.trim().length < 4}
            >
              join!
            </SketchButton>
          </div>
        </div>
      </div>
    )
  }

  // ── Connecting ──
  if (connection === 'connecting' || !state || !config) {
    return (
      <div className="page-shell justify-center" data-testid="connecting-screen">
        <PosterBurst />
        <div className="poster-content text-center">
          <h2 className="mb-3 sway-mid">connecting…</h2>
          <p className="text-muted mb-6">finding the table</p>
          <SketchButton variant="ghost" testId="connect-cancel" onClick={leave}>cancel</SketchButton>
        </div>
      </div>
    )
  }

  // ── Waiting room ──
  const invite = roomCode ? inviteUrl(roomCode) : ''

  // Only a copy leaves a mark. A share sheet has already told the host what it
  // did, and one that was cancelled did nothing at all.
  async function hand(what: 'code' | 'link', text: string, sheet: ShareData) {
    if ((await handOut(text, sheet)) === 'copied') {
      setShared(what)
      setTimeout(() => setShared(null), 2000)
    }
  }

  const winLabel = config.winCondition === 'first_to_score'
    ? `first to ${config.targetScore}`
    : `best of ${config.totalRounds} rounds`
  const minPlayers = catalog?.minPlayers ?? 2
  const maxPlayers = catalog?.maxPlayers ?? 5
  const missing = Math.max(0, minPlayers - players.length)

  return (
    <>
      {/* Outside the shell rather than inside it. Three-two-one across the whole
          window is not part of the waiting room — it is what replaces it — and
          a `fixed` overlay written as a child of a page that scrolls is one
          ancestor `transform` or `filter` away from being anchored to that page
          instead of to the window. */}
      {countdown && <Countdown onDone={startGame} />}

    <div className="page-shell justify-start" data-testid="waiting-room" data-host={isHost}>
      <PosterBurst />

      <div className="poster-content max-w-[460px] w-full">
        <div className="text-center mb-8">
          {/* Whose table this is, set the way the front door was. The name is
              somebody's to type, so it goes on the small line where a long one
              costs a lockup rather than a layout. */}
          <div className="flex justify-center">
            <TitleMark
              small={playerName ? `${playerName}'s` : 'let it'}
              big={playerName ? 'game' : 'ride'}
              scale={0.5}
            />
          </div>
          {roomCode && (
            <>
              <button
                onClick={() => hand('code', roomCode, { text: roomCode })}
                className="mt-3 bg-transparent border-none cursor-pointer block mx-auto"
              >
                <label>room code: </label>
                <span data-testid="room-code" className="display text-4xl tracking-[0.25em] room-code-border pb-1 select-all">{roomCode}</span>
                {/* Four letters somebody has to read out or send on. What the tap
                    actually does depends on where the game is being served from —
                    see [handOut] — so the caption only promises the one thing
                    that is always true. */}
                <small className="block mt-2">{shared === 'code' ? 'copied!' : 'tap to share it with your friends'}</small>
              </button>

              {/* An anchor, and a real `href`, for a link that is never
                  followed here: reading out four letters is the thing you do in
                  a room, and pasting an address is the thing you do everywhere
                  else, so the second one has to be a link the browser itself
                  recognises. That is what gives a long press its "copy link
                  address" and a right click its menu — the last way through
                  when the clipboard is barred and there is no share sheet,
                  which is exactly the plain-http LAN box this gets played on.
                  The click is ours because a tap should share it, not navigate
                  the host away from their own table. */}
              <a
                href={invite}
                data-testid="invite-link"
                onClick={(e) => {
                  e.preventDefault()
                  hand('link', invite, { title: 'let it ride', text: `come and play — room ${roomCode}`, url: invite })
                }}
                className="tap-target mt-1 display text-base text-[var(--accent)] no-underline -rotate-1"
              >
                {shared === 'link' ? 'link copied!' : 'or send them a link →'}
              </a>
            </>
          )}
        </div>

        {/* Rules of this table */}
        <div className="sketch-box mb-4 rounded p-5 relative">
          <h2 className="mb-4 -rotate-1">~ rules for this game ~</h2>

          <p className="mb-2">
            <span className="text-muted">mode: </span>
            <span className="display text-xl" data-testid="table-mode">
              {modeOf(config) === 'rollingRules' ? 'rolling rules' : 'let it ride'}
            </span>
          </p>

          <div className="flex items-center justify-between mb-3">
            <p>
              <span className="text-muted">deck: </span>
              <span className="display text-xl" data-testid="table-deck-name">
                {preset?.name ?? 'a deck of your own'}
              </span>
              <small className="ml-1.5">({preset?.cardCount ?? builtCardCount} cards)</small>
            </p>
            <button
              onClick={() => setShowDeckCards(!showDeckCards)}
              data-testid="toggle-deck-cards"
              className="tap-target shrink-0 bg-transparent border-none cursor-pointer display text-base text-[var(--accent)]"
            >
              {showDeckCards ? 'hide cards' : 'see cards'}
            </button>
          </div>

          {showDeckCards && (
            <div className="sketch-box-light flex flex-wrap gap-1.5 p-2 mb-4 rounded">
              {(preset?.contents ?? builtContents).map((entry) => (
                <div key={entry.card.id} className="relative">
                  <PlayingCard card={entry.card} size="small" />
                  <span className="absolute -bottom-0.5 -right-0.5 z-10 display text-[10px] text-[var(--card-face)] bg-[var(--ink)] rounded-full px-1 leading-[14px] min-w-[16px] text-center">
                    {entry.count}x
                  </span>
                </div>
              ))}
            </div>
          )}

          <p className="mb-1">
            <span className="text-muted">goal: </span>
            <span className="display text-xl" data-testid="table-goal">{winLabel}</span>
          </p>
          <p className="mb-1">
            <span className="text-muted">turn timer: </span>
            <span className="display text-xl" data-testid="table-timer">{config.turnTimeSeconds}s</span>
          </p>
          <p className="mb-1">
            <span className="text-muted">next round: </span>
            <span className="display text-xl" data-testid="table-autostart">
              {config.autoNextRoundSeconds ? `auto after ${config.autoNextRoundSeconds}s` : 'when the host says'}
            </span>
          </p>
          {config.ruleIds.length > 0 && (
            <p>
              <span className="text-muted">house rules: </span>
              <span className="display text-xl" data-testid="table-house-rules">
                {config.ruleIds.map((id) => findRule(catalog, id)?.name ?? id).join(', ')}
              </span>
            </p>
          )}

          <div className="mt-4 flex gap-3.5">
            <SketchButton variant="ghost" testId="open-settings" onClick={() => setScreen('settings')}>
              {isHost ? 'change settings' : 'see settings'}
            </SketchButton>
            <SketchButton variant="ghost" testId="open-rules" onClick={() => setShowRules(true)}>rules</SketchButton>
          </div>
        </div>

        {/* Players */}
        <div className="sketch-box mb-4 rounded p-5 relative">
          <h2 className="mb-3 -rotate-1">~ players ({players.length}/{maxPlayers}) ~</h2>
          {players.length === 0 ? (
            <p className="text-muted text-lg py-3 italic">waiting for friends…</p>
          ) : (
            players.map((p, i) => (
              <div
                key={p.id}
                data-testid="lobby-player"
                data-player-id={p.id}
                data-player-name={p.name}
                data-bot={p.isBot}
                className="flex items-center gap-2 py-1.5 display text-xl"
              >
                <small>{i + 1}.</small>
                <span className="flex-1">{p.name}</span>
                {p.isBot && <small className="font-normal">bot</small>}
                {p.id === state.hostId && <small className="font-normal">(host)</small>}
                {isHost && p.id !== state.hostId && (
                  <button
                    onClick={() => send({ type: 'KICK', playerId: p.id })}
                    data-testid="kick-player"
                    className="tap-target bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] -rotate-1"
                  >
                    kick
                  </button>
                )}
              </div>
            ))
          )}
          {isHost && players.length < maxPlayers && (
            <button
              onClick={() => send({ type: 'ADD_BOT' })}
              data-testid="add-bot"
              className="tap-target mt-2 bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] -rotate-1"
            >
              + add a bot
            </button>
          )}
        </div>

        {error && <p className="text-[var(--accent)] text-center mb-3" data-testid="lobby-error">{error}</p>}

        <div className="flex flex-wrap justify-center gap-3.5">
          {isHost ? (
            <SketchButton variant="primary" testId="start-game" onClick={() => setCountdown(true)} disabled={missing > 0}>
              {missing > 0 ? `need ${missing} more` : 'let it ride!'}
            </SketchButton>
          ) : (
            <p className="flex-1 flex items-center justify-center text-muted text-lg" data-testid="waiting-for-host">
              waiting for host to start…
            </p>
          )}
          <SketchButton variant="ghost" testId="leave-room" onClick={leave}>leave</SketchButton>
        </div>
      </div>
    </div>
    </>
  )
}
