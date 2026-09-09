import { GAMBLER_WINDOWS_SHORT, type Card as CardType } from "../../game/types";
import {
  findAction,
  findGambler,
  findPassive,
  useCatalog,
} from "../../state/gameStore";
import { theme } from "../../theme";
import { RoughBox, RoughSeal, RoughSquiggle } from "../ui/RoughShapes";
import { CardBack } from "./CardBack";
import { cardHash } from "./dealtCards";

interface PlayingCardProps {
  card: CardType;
  size?: "small" | "normal" | "deck" | "large";
  faceDown?: boolean;
  dimmed?: boolean;
  glowing?: boolean;
  style?: React.CSSProperties;
}

const DIMS = {
  small: { w: 52, h: 76, fs: 14, num: 30, corner: 13, sigil: 26 },
  normal: { w: 92, h: 132, fs: 22, num: 64, corner: 19, sigil: 50 },
  deck: { w: 100, h: 142, fs: 22, num: 68, corner: 20, sigil: 48 },
  // For a card you are being asked to read rather than to recognise: the shop
  // shelf, where every face is one you have never seen and the description is
  // the whole of the decision. `deck` is the biggest a card gets *on the table*,
  // and on a shelf that left half the screen empty and the text at nine points.
  large: { w: 148, h: 210, fs: 32, num: 100, corner: 28, sigil: 72 },
};

const SUIT_GLYPHS: Record<string, string> = {
  hearts: "\u2020",
  diamonds: "\u2726",
  clubs: "\u2620",
  spades: "\u273A",
};

export function PlayingCard({
  card,
  size = "normal",
  faceDown: faceDownProp = false,
  dimmed = false,
  glowing = false,
  style = {},
}: PlayingCardProps) {
  // A card the server did not send a face for — see the "redacted" card. It is
  // read here and nowhere else on purpose: a hidden card can turn up in a hand,
  // in a flight across the table, on the slot machine's reels or in the middle
  // of a save being torn in half, and every one of those draws its card through
  // this component. One line here covers all of them; a check at each of those
  // call sites would be four places to forget.
  const faceDown = faceDownProp || !!card.hidden;
  const dims = DIMS[size];
  const sw = theme.strokeWidth;
  const ink = theme.ink;
  const catalog = useCatalog();

  // The face comes from the backend's catalog. Looked up before anything is
  // drawn, because a passive is a different kind of object from an action card
  // rather than the same card in another colour.
  //
  // A gambler card is found by its *kind* rather than by whichever catalog
  // answers first. There are three catalogs now and nothing stops one of them
  // one day holding an id another already has; a face resolved by lookup order
  // would then quietly draw the wrong card, which is the worst kind of wrong.
  const gambler =
    card.kind === "gambler" ? findGambler(catalog, card.defId) : undefined;
  const action = gambler ? undefined : findAction(catalog, card.defId);
  const passive = gambler || action ? undefined : findPassive(catalog, card.defId);
  // Each passive prints in its own ink so the row in front of a player reads as
  // several things rather than one green block. A server that does not send one
  // falls back to the house green every passive used to share.
  const accent = gambler
    ? gambler.accent ?? theme.passiveAccent
    : passive
      ? passive.accent ?? theme.passiveAccent
      : theme.actionAccent;

  const phase = (cardHash(card.id) % 1000) / 1000;
  const swayDur = 2.2 + phase * 1.6;
  const swayDelay = -phase * swayDur;

  const baseStyle: React.CSSProperties = {
    width: dims.w,
    height: dims.h,
    position: "relative",
    flexShrink: 0,
    background: "transparent",
    borderRadius: 4,
    // Passive green is a lighter ink than the black the rest of the table is
    // drawn in, and goes further at the same fade — it is held back a little
    // less so a dimmed passive still reads as a card rather than a smudge.
    opacity: dimmed ? (passive ? 0.55 : 0.45) : 1,
    transition: "transform 220ms cubic-bezier(.2,.9,.3,1.2)",
    boxShadow: glowing ? `3px 3px 0 0 ${accent}` : "none",
    animation: `sway ${swayDur}s ease-in-out ${swayDelay}s infinite`,
    ...style,
  };

  const paperFill = (
    <div
      style={{
        position: "absolute",
        inset: sw,
        background: theme.cardFace,
        borderRadius: 3,
        zIndex: 0,
      }}
    />
  );

  // Face down — the house back, drawn by the thing that draws the house back.
  // It used to be a second copy of it here, which is how it came to be a
  // slightly different card from the one on top of the draw pile.
  //
  // The sway is the card's own rather than [CardBack]'s fixed one: a hand of
  // five hidden cards all breathing in step reads as one object, and the point
  // of drawing them as cards at all is that you can count them.
  if (faceDown) {
    return (
      <CardBack
        size={size === "large" ? "deck" : size}
        style={{
          animation: `sway ${swayDur}s ease-in-out ${swayDelay}s infinite`,
          ...style,
        }}
      />
    );
  }

  // Gambler card. Drawn like a passive — tinted paper, framed, the sigil struck
  // as a seal — because it is the same kind of object: a card you are holding
  // rather than one that is happening. What is different is that it says how
  // rare it is, and it says so in the frame and the strike rather than in the
  // seal's shape.
  //
  // The shape is already spoken for: a shield guards, a token pays, a spike
  // bites. Rarity riding on the same channel would make a spike mean "nasty" on
  // one card and "valuable" on the next. So rarity is *pressure* — one more
  // frame, one more strike of the stamp — which reads from across the table
  // and leaves the shape saying what the card does.
  if (gambler) {
    const rare = gambler.rarity !== "common";
    const jackpot = gambler.rarity === "jackpot";
    const seal = Math.round(dims.sigil * (size === "small" ? 1.1 : 0.82));
    const glyph = Math.round(seal * (gambler.sigil.length > 1 ? 0.44 : 0.56));
    const frame = size === "small" ? 6 : 8;
    return (
      <div style={baseStyle}>
        <div
          style={{
            position: "absolute",
            inset: sw,
            background: `color-mix(in srgb, ${accent} ${jackpot ? 14 : 9}%, ${theme.cardFace})`,
            borderRadius: 3,
            zIndex: 0,
          }}
        />
        <RoughBox
          width={dims.w}
          height={dims.h}
          stroke={accent}
          strokeWidth={sw * (jackpot ? 1.25 : 1)}
          roughness={1.9}
        />
        {rare && (
          <RoughBox
            width={dims.w - frame * 2}
            height={dims.h - frame * 2}
            stroke={accent}
            strokeWidth={sw * (jackpot ? 0.7 : 0.5)}
            roughness={2.4}
            dashed={!jackpot}
            style={{ top: frame, left: frame, opacity: jackpot ? 0.9 : 0.7 }}
          />
        )}
        <div
          style={{
            position: "absolute",
            inset: sw + frame - 1,
            display: "flex",
            flexDirection: "column",
            alignItems: "stretch",
            zIndex: 2,
          }}
        >
          <div
            style={{
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * (size === "small" ? 0.54 : 0.62),
              color: accent,
              fontWeight: 700,
              textAlign: "center",
              padding: "1px 5px 0",
              letterSpacing: "0.03em",
              textTransform: "uppercase",
              lineHeight: 1.05,
            }}
          >
            {gambler.name}
          </div>
          <div
            style={{
              flex: 1,
              minHeight: 0,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 2,
            }}
          >
            <div
              style={{
                position: "relative",
                flexShrink: 0,
                width: seal,
                height: seal,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <RoughSeal
                size={seal}
                shape={gambler.seal ?? "hexagon"}
                stroke={accent}
                strokeWidth={sw * (jackpot ? 1.05 : rare ? 0.9 : 0.75)}
                strikes={rare ? 3 : 2}
                roughness={2}
              />
              <span
                style={{
                  fontFamily: theme.fontDisplay,
                  fontSize: glyph,
                  color: accent,
                  fontWeight: 700,
                  lineHeight: 1,
                  position: "relative",
                  zIndex: 1,
                }}
              >
                {gambler.sigil}
              </span>
            </div>
            {/* The word as well as the pressure. A stamp struck harder is a
                thing you learn; the word is a thing you can be told.

                And beside it, when the card may be played. Half of what a
                gambler card costs you is that it is only good at one moment,
                and a face that did not say which moment left you to find out
                by clicking it and having nothing happen. */}
            {size !== "small" && (
              <div
                style={{
                  fontFamily: theme.fontBody,
                  fontSize: dims.fs * 0.34,
                  color: accent,
                  opacity: 0.85,
                  letterSpacing: "0.1em",
                  textTransform: "lowercase",
                  lineHeight: 1.1,
                  textAlign: "center",
                  padding: "0 3px",
                }}
              >
                {gambler.rarity} · {GAMBLER_WINDOWS_SHORT[gambler.window] ?? gambler.window}
              </div>
            )}
          </div>
          {size !== "small" && (
            <div
              style={{
                padding: "0 5px 6px",
                fontFamily: theme.fontBody,
                fontSize: dims.fs * 0.42,
                color: `color-mix(in srgb, ${accent} 80%, ${theme.ink})`,
                textAlign: "center",
                lineHeight: 1.05,
              }}
            >
              {gambler.description}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Passive card. It is not played and it does not leave — it sits in front of
  // its owner for the rest of the round — so it is drawn as its own kind of
  // object rather than an action card in another colour: green ink on tinted
  // paper, the border doubled with a dashed inner frame, and the sigil struck
  // as a seal instead of printed loose.
  if (passive) {
    // The seal stands in for the loose sigil an action card prints, but it has
    // to leave the name its two lines above and the description its two below —
    // the small card has neither and can give the seal the room. A sigil of
    // more than one character ("×2", "+4") is set smaller to stay off the ring.
    const seal = Math.round(dims.sigil * (size === 'small' ? 1.15 : 0.9));
    const glyph = Math.round(seal * (passive.sigil.length > 1 ? 0.44 : 0.56));
    // Far enough in that the two frames read as two lines rather than one shaky
    // one — RoughBox already insets its rectangle by the stroke.
    const frame = size === 'small' ? 6 : 8;
    return (
      <div style={baseStyle}>
        <div
          style={{
            position: 'absolute',
            inset: sw,
            background: `color-mix(in srgb, ${accent} 8%, ${theme.cardFace})`,
            borderRadius: 3,
            zIndex: 0,
          }}
        />
        <RoughBox
          width={dims.w}
          height={dims.h}
          stroke={accent}
          strokeWidth={sw}
          roughness={1.9}
        />
        <RoughBox
          width={dims.w - frame * 2}
          height={dims.h - frame * 2}
          stroke={accent}
          strokeWidth={sw * 0.5}
          roughness={2.4}
          dashed
          style={{ top: frame, left: frame, opacity: 0.75 }}
        />
        <div
          style={{
            // Inside the dashed frame: the writing is on the ticket, not across
            // its edge.
            position: 'absolute',
            inset: sw + frame - 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'stretch',
            zIndex: 2,
          }}
        >
          {/* The bonus passives are named after their own sigil ("+4"), and
              printing it twice just crowds the seal. */}
          {passive.name !== passive.sigil && (
            <div
              style={{
                fontFamily: theme.fontDisplay,
                // Set in capitals, which run wide — the name has to hold its
                // two lines inside a card 52px across at its smallest.
                fontSize: dims.fs * (size === 'small' ? 0.56 : 0.66),
                color: accent,
                fontWeight: 700,
                textAlign: 'center',
                padding: '1px 5px 0',
                letterSpacing: '0.03em',
                textTransform: 'uppercase',
                lineHeight: 1.05,
              }}
            >
              {passive.name}
            </div>
          )}
          <div
            style={{
              flex: 1,
              minHeight: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <div
              style={{
                position: 'relative',
                flexShrink: 0,
                width: seal,
                height: seal,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <RoughSeal
                size={seal}
                shape={passive.seal ?? 'circle'}
                stroke={accent}
                // The bonus cards all wear the same green ring, so what tells a
                // +2 from a +10 across the table is how hard it was struck.
                strokeWidth={sw * (0.7 + (passive.bonusPoints / 10) * 0.55)}
                roughness={2}
              />
              <span
                style={{
                  fontFamily: theme.fontDisplay,
                  fontSize: glyph,
                  color: accent,
                  fontWeight: 700,
                  lineHeight: 1,
                  position: 'relative',
                  zIndex: 1,
                }}
              >
                {passive.sigil}
              </span>
            </div>
          </div>
          {size !== 'small' && (
            <div
              style={{
                padding: '0 5px 6px',
                fontFamily: theme.fontBody,
                fontSize: dims.fs * 0.44,
                color: `color-mix(in srgb, ${accent} 80%, ${theme.ink})`,
                textAlign: 'center',
                lineHeight: 1.05,
              }}
            >
              {passive.description}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Action card — the face comes from the backend's catalog.
  const cardDef = action;
  if (cardDef) {
    return (
      <div style={baseStyle}>
        {paperFill}
        <RoughBox
          width={dims.w}
          height={dims.h}
          stroke={ink}
          strokeWidth={sw}
          roughness={1.7}
        />
        <div
          style={{
            position: "absolute",
            inset: sw + 4,
            display: "flex",
            flexDirection: "column",
            alignItems: "stretch",
            zIndex: 2,
          }}
        >
          <div
            style={{
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.78,
              color: ink,
              fontWeight: 700,
              textAlign: "center",
              padding: "4px 4px 2px",
              letterSpacing: "0.02em",
              lineHeight: 1,
              position: "relative",
            }}
          >
            {cardDef.name}
            <div
              style={{
                position: "absolute",
                bottom: -3,
                left: "10%",
                width: "80%",
                height: 8,
              }}
            >
              <RoughSquiggle
                width={Math.round(dims.w * 0.7)}
                height={8}
                stroke={ink}
                strokeWidth={sw * 0.6}
                amplitude={1.5}
                segments={5}
                roughness={1.4}
              />
            </div>
          </div>
          <div
            style={{
              flex: 1,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: dims.sigil,
              color: theme.actionAccent,
              fontFamily: theme.fontDisplay,
              fontWeight: 700,
              lineHeight: 1,
            }}
          >
            {cardDef.sigil}
          </div>
          {size !== "small" && (
            <div
              style={{
                padding: "0 6px 6px",
                fontFamily: theme.fontBody,
                fontSize: dims.fs * 0.46,
                color: ink,
                textAlign: "center",
                lineHeight: 1.05,
              }}
            >
              {cardDef.description}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Number card
  const value = card.value;
  // `label` is what is printed: the value for the numeric decks, the rank for
  // the classic 52-card deck. It is also what duplicates are matched on.
  const face = card.label || String(card.value);
  const suitGlyph = (card.suit && SUIT_GLYPHS[card.suit]) || "";
  const isHot = value >= 7;
  const numColor = isHot ? theme.actionAccent : ink;
  const digitRotation = ((value * 13) % 7) - 3;

  return (
    <div style={baseStyle}>
      {paperFill}
      <RoughBox
        width={dims.w}
        height={dims.h}
        stroke={ink}
        strokeWidth={sw}
        roughness={1.7}
      />
      <div style={{ position: "absolute", inset: sw + 4, zIndex: 2 }}>
        {/* Corner top-left */}
        <div
          style={{
            position: "absolute",
            top: 2,
            left: 5,
            fontFamily: theme.fontNumber,
            fontWeight: 700,
            fontSize: dims.corner,
            lineHeight: 1,
            color: numColor,
          }}
        >
          {face}
        </div>
        {/* Corner bottom-right */}
        <div
          style={{
            position: "absolute",
            bottom: 2,
            right: 5,
            fontFamily: theme.fontNumber,
            fontWeight: 700,
            fontSize: dims.corner,
            lineHeight: 1,
            color: numColor,
            transform: "rotate(180deg)",
          }}
        >
          {face}
        </div>
        {/* Center number */}
        {size !== "small" && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontFamily: theme.fontNumber,
              fontSize: dims.num,
              color: numColor,
              fontWeight: 700,
              lineHeight: 0.9,
              transform: `rotate(${digitRotation}deg)`,
            }}
          >
            {face}
          </div>
        )}
        {size === "small" && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontFamily: theme.fontNumber,
              fontSize: dims.num,
              color: numColor,
              fontWeight: 700,
              lineHeight: 0.9,
              transform: `rotate(${digitRotation}deg)`,
            }}
          >
            {face}
          </div>
        )}
        {/* Suit glyph */}
        {size !== "small" && (
          <div
            style={{
              position: "absolute",
              bottom: 8,
              left: 0,
              right: 0,
              textAlign: "center",
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.48,
              color: isHot ? theme.actionAccent : theme.inkSoft,
              opacity: 0.85,
            }}
          >
            {suitGlyph}
          </div>
        )}
        {/* Hot mark */}
        {isHot && size !== "small" && (
          <div
            style={{
              position: "absolute",
              top: 4,
              right: 6,
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.5,
              color: theme.actionAccent,
              fontWeight: 700,
              lineHeight: 1,
              transform: "rotate(6deg)",
            }}
          >
            !
          </div>
        )}
      </div>
    </div>
  );
}
