import { expect, type Locator, type Page } from '@playwright/test'

/**
 * What a screen has to be true of before anybody can play on it.
 *
 * These are the assertions the mobile pass exists to keep passing, and they are
 * here rather than written out in each spec because every one of them wants the
 * same three: nothing pushes the page sideways, everything you have to press is
 * on the screen, and everything you have to press is big enough to press.
 */

/**
 * Nothing may push the document sideways.
 *
 * The one horizontal-overflow check the suite had was on the title card, which
 * is the screen least likely to have the problem. A pixel of slack because
 * rounding at a fractional device pixel ratio is not a bug.
 */
export async function expectNoHorizontalScroll(page: Page, what = 'the page'): Promise<void> {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow, `${what} scrolls horizontally`).toBeLessThanOrEqual(1)
}

/** Everything of [locator] is inside the window, with [slack] px of grace. */
export async function expectOnScreen(page: Page, locator: Locator, what: string, slack = 1): Promise<void> {
  const box = await locator.boundingBox()
  expect(box, `${what} is not laid out at all`).not.toBeNull()
  const size = page.viewportSize()
  expect(size, 'the project set no viewport').not.toBeNull()
  expect(box!.x, `${what} runs off the left`).toBeGreaterThanOrEqual(-slack)
  expect(box!.y, `${what} runs off the top`).toBeGreaterThanOrEqual(-slack)
  expect(box!.x + box!.width, `${what} runs off the right`).toBeLessThanOrEqual(size!.width + slack)
  expect(box!.y + box!.height, `${what} runs off the bottom`).toBeLessThanOrEqual(size!.height + slack)
}

/**
 * [locator] is at least [min] square.
 *
 * 44px is the smallest thing somebody hits without looking, which is the way
 * every control in a card game is hit. Kept as an assertion rather than a
 * review note because the failure mode is silent: a 20px stepper still works
 * perfectly with a mouse.
 */
export async function expectTapTarget(locator: Locator, what: string, min = 44): Promise<void> {
  const box = await locator.boundingBox()
  expect(box, `${what} is not laid out at all`).not.toBeNull()
  expect(Math.min(box!.width, box!.height), `${what} is smaller than ${min}px`).toBeGreaterThanOrEqual(min - 0.5)
}

/** Whether two boxes on screen overlap at all. */
export async function expectNotOverlapping(a: Locator, b: Locator, what: string): Promise<void> {
  const [boxA, boxB] = [await a.boundingBox(), await b.boundingBox()]
  expect(boxA, `${what}: the first is not laid out`).not.toBeNull()
  expect(boxB, `${what}: the second is not laid out`).not.toBeNull()
  const apart =
    boxA!.x + boxA!.width <= boxB!.x + 1 ||
    boxB!.x + boxB!.width <= boxA!.x + 1 ||
    boxA!.y + boxA!.height <= boxB!.y + 1 ||
    boxB!.y + boxB!.height <= boxA!.y + 1
  expect(apart, `${what} overlap`).toBe(true)
}
