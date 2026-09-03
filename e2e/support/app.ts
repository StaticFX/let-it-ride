import { expect, type Locator, type Page } from '@playwright/test'
import { Table, type Policy, type Screen } from './table'
import type { Api } from './api'
import type { Scenario } from './seeds'

/**
 * One player's browser. Wraps the screens they move through — the title card,
 * the waiting room, settings — and hands off to [Table] once a game starts.
 */
export class App {
  readonly table: Table

  constructor(readonly page: Page, private readonly api: Api) {
    this.table = new Table(page)
  }

  // ─── Getting in ───

  async open(): Promise<void> {
    await this.page.goto('/')
    await expect(this.page.getByTestId('title-screen')).toBeVisible({ timeout: 30_000 })
  }

  async enterName(name: string): Promise<void> {
    await this.page.getByTestId('name-input').fill(name)
  }

  /** Opens a table of your own and lands in the waiting room. */
  async host(name: string): Promise<string> {
    await this.enterName(name)
    await this.page.getByTestId('host-game').click()
    return this.waitForWaitingRoom()
  }

  /** Opens a table with the default three bots already seated. */
  async hostVersusBots(name: string, bots = 3): Promise<string> {
    await this.enterName(name)
    await this.page.getByTestId('play-vs-bots').click()
    const code = await this.waitForWaitingRoom()
    await expect(this.page.locator('[data-testid="lobby-player"][data-bot="true"]')).toHaveCount(bots)
    return code
  }

  /**
   * Opens a room with a fixed shuffle and joins it as the host.
   *
   * The room is created over the API because that is the only door the seed
   * goes through; the browser then walks in the normal way, so everything
   * after this point is the real join flow.
   */
  async hostSeeded(name: string, seed: number): Promise<string> {
    const room = await this.api.createRoom(name, seed)
    await this.join(name, room.roomCode)
    await expect(this.page.getByTestId('start-game')).toBeVisible()
    return room.roomCode
  }

  /**
   * Sets a table up exactly as `scripts/find-seeds.ts` did when it found the
   * seed — same deck, same number of bots, same order — so the round it
   * describes is the round that gets played.
   */
  async setUpScenario(scenario: Scenario, name = 'devin'): Promise<string> {
    const code = await this.hostSeeded(name, scenario.seed)
    await this.addBotsUntil(1 + scenario.bots)
    // A generous clock: a timeout would take the local player's turn away and
    // the round would no longer be the one the seed describes.
    await this.configure({ deck: scenario.deck, turnSeconds: 120 })
    return code
  }

  async join(name: string, code: string): Promise<void> {
    await this.enterName(name)
    await this.page.getByTestId('join-game').click()
    await this.page.getByTestId('join-code-input').fill(code)
    await this.page.getByTestId('join-submit').click()
  }

  private async waitForWaitingRoom(): Promise<string> {
    await expect(this.page.getByTestId('waiting-room')).toBeVisible({ timeout: 30_000 })
    return this.roomCode()
  }

  async roomCode(): Promise<string> {
    const code = await this.page.getByTestId('room-code').innerText()
    return code.trim()
  }

  // ─── The waiting room ───

  get players(): Locator { return this.page.getByTestId('lobby-player') }

  playerRow(name: string): Locator {
    return this.page.locator(`[data-testid="lobby-player"][data-player-name="${name}"]`)
  }

  async addBot(): Promise<void> {
    const before = await this.players.count()
    await this.page.getByTestId('add-bot').click()
    await expect(this.players).toHaveCount(before + 1)
  }

  async addBotsUntil(total: number): Promise<void> {
    while ((await this.players.count()) < total) await this.addBot()
  }

  async kick(name: string): Promise<void> {
    await this.playerRow(name).getByTestId('kick-player').click()
  }

  async isHost(): Promise<boolean> {
    return (await this.page.getByTestId('waiting-room').getAttribute('data-host')) === 'true'
  }

  /**
   * Starts the game and sits through the countdown, the title card and the
   * deal, so the caller picks up a table that is ready to play.
   *
   * Note that this answers a target prompt raised *by the deal* — an opening
   * card that turns out to be an action card. A spec that wants to watch that
   * happen should use [startAndWatch] instead.
   */
  async start(): Promise<void> {
    await this.page.getByTestId('start-game').click()
    await expect(this.page.getByTestId('countdown')).toBeVisible()
    await this.table.waitForPlay()
  }

  /**
   * Starts the game and hands back as soon as the table is on screen — before
   * the deal — so nothing is answered on the spec's behalf.
   */
  async startAndWatch(): Promise<void> {
    await this.page.getByTestId('start-game').click()
    await expect(this.page.getByTestId('game-board')).toBeVisible({ timeout: 45_000 })
  }

  async leaveRoom(): Promise<void> {
    await this.page.getByTestId('leave-room').click()
    await expect(this.page.getByTestId('title-screen')).toBeVisible()
  }

  // ─── Settings ───

  async openSettings(): Promise<void> {
    await this.page.getByTestId('open-settings').click()
    await expect(this.page.getByTestId('settings-screen')).toBeVisible()
  }

  async closeSettings(): Promise<void> {
    await this.page.getByTestId('settings-done').click()
    await expect(this.page.getByTestId('waiting-room')).toBeVisible()
  }

  async chooseDeck(deckId: string): Promise<void> {
    await this.page.getByTestId(`deck-${deckId}`).click()
    await expect(this.page.getByTestId(`deck-${deckId}`)).toHaveAttribute('data-selected', 'true')
  }

  async toggleRule(ruleId: string): Promise<void> {
    const rule = this.page.getByTestId(`rule-${ruleId}`)
    const before = await rule.getAttribute('data-active')
    await rule.click()
    await expect(rule).toHaveAttribute('data-active', before === 'true' ? 'false' : 'true')
  }

  async chooseWinCondition(which: 'rounds' | 'score'): Promise<void> {
    await this.page.getByTestId(which === 'rounds' ? 'win-rounds' : 'win-score').click()
  }

  /**
   * Drags a sketch slider to [value].
   *
   * There is no native input behind the control — it maps a pointer's x against
   * its own bounding box and snaps to the nearest step — so this presses at the
   * matching point on that box. The track is only a couple of hundred pixels
   * wide, so a step can be worth less than a pixel; the loop nudges until it
   * lands rather than trusting one press.
   */
  async setSlider(testId: string, value: number): Promise<void> {
    const slider = this.page.getByTestId(testId)
    await expect(slider).toBeVisible()
    // Deck listings are tall enough to push the sliders below the fold, and the
    // mouse works in viewport coordinates.
    await slider.scrollIntoViewIfNeeded()

    const min = Number(await slider.getAttribute('data-min'))
    const max = Number(await slider.getAttribute('data-max'))
    const target = Math.min(max, Math.max(min, value))

    const press = async (x: number, y: number) => {
      await this.page.mouse.move(x, y)
      await this.page.mouse.down()
      await this.page.mouse.move(x, y)
      await this.page.mouse.up()
    }

    for (let attempt = 0; attempt < 10; attempt++) {
      if (Number(await slider.getAttribute('data-value')) === target) return

      // Re-read the box every time: choosing a deck changes the page's height.
      const box = await slider.boundingBox()
      if (!box) throw new Error(`slider ${testId} has no box to aim at`)

      const fraction = (target - min) / (max - min)
      // The last pixel of the box rounds to the max but can fall outside the
      // element, so stay a pixel inside it.
      const x = box.x + Math.min(box.width - 1, Math.max(1, fraction * box.width))
      const landed = Number(await slider.getAttribute('data-value'))
      const correction = attempt === 0 ? 0 : ((target - landed) / (max - min)) * box.width
      await press(
        Math.min(box.x + box.width - 1, Math.max(box.x + 1, x + correction)),
        box.y + box.height / 2,
      )
    }

    await expect(slider, `slider ${testId} would not settle on ${target}`)
      .toHaveAttribute('data-value', String(target))
  }

  /** Sets up a short game: one deck, one win condition, a generous clock. */
  async configure(options: {
    deck?: string
    rounds?: number
    targetScore?: number
    turnSeconds?: number
    rules?: string[]
  }): Promise<void> {
    await this.openSettings()
    if (options.deck) await this.chooseDeck(options.deck)
    if (options.rounds !== undefined) {
      await this.chooseWinCondition('rounds')
      await this.setSlider('rounds-slider', options.rounds)
    }
    if (options.targetScore !== undefined) {
      await this.chooseWinCondition('score')
      await this.setSlider('target-score-slider', options.targetScore)
    }
    if (options.turnSeconds !== undefined) await this.setSlider('turn-timer-slider', options.turnSeconds)
    for (const rule of options.rules ?? []) await this.toggleRule(rule)
    await this.closeSettings()
  }

  // ─── Between rounds ───

  async nextRound(): Promise<void> {
    await this.page.getByTestId('next-round').click()
  }

  /** Waits until the app is showing one of [screens]. */
  async waitForScreen(screens: Screen[], timeoutMs = 60_000): Promise<Screen> {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const { screen } = await this.table.snapshot()
      if (screens.includes(screen)) return screen
      if (Date.now() > deadline) {
        throw new Error(`waited ${timeoutMs}ms for one of [${screens.join(', ')}] but the screen was '${screen}'`)
      }
      await this.page.waitForTimeout(200)
    }
  }

  /**
   * Plays round after round until the game is over, taking the host's
   * "next round" button whenever it appears.
   */
  async playToGameOver(options: { policy?: Policy; maxRounds?: number } = {}): Promise<void> {
    const maxRounds = options.maxRounds ?? 25
    for (let round = 0; round < maxRounds; round++) {
      const end = await this.table.playRound({ policy: options.policy })
      if (end.screen === 'gameOver') return

      const nextRound = this.page.getByTestId('next-round')
      if (await nextRound.isVisible()) await nextRound.click()

      if ((await this.waitForScreen(['board', 'gameOver'])) === 'gameOver') return
      await this.table.waitForPlay()
    }
    throw new Error(`the game had not finished after ${maxRounds} rounds`)
  }

  // ─── Overlays ───

  async openPauseMenu(): Promise<void> {
    await this.page.keyboard.press('Escape')
    await expect(this.page.getByTestId('escape-menu')).toBeVisible()
  }

  async closePauseMenu(): Promise<void> {
    await this.page.getByTestId('pause-resume').click()
    await expect(this.page.getByTestId('escape-menu')).toBeHidden()
  }

  async openRules(): Promise<void> {
    await this.page.getByTestId('open-rules').first().click()
    await expect(this.page.getByTestId('rules-page')).toBeVisible()
  }

  async closeRules(): Promise<void> {
    await this.page.getByTestId('rules-back').click()
    await expect(this.page.getByTestId('rules-page')).toBeHidden()
  }

  /**
   * Cuts the websocket the way a flaky network does — without telling the
   * client — so the reconnect path runs for real.
   */
  async dropConnection(): Promise<void> {
    await this.page.evaluate(() => {
      const registry = (window as unknown as { __sockets?: WebSocket[] }).__sockets
      if (registry) for (const socket of registry) socket.close()
    })
  }
}
