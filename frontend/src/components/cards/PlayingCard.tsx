import type { Card as CardType } from "../../game/types";
import { findAction, findPassive, useCatalog } from "../../state/gameStore";
import { theme } from "../../theme";
import { RoughBox, RoughSeal, RoughSquiggle } from "../ui/RoughShapes";
import { cardHash } from "./dealtCards";

interface PlayingCardProps {
  card: CardType;
  size?: "small" | "normal" | "deck";
  faceDown?: boolean;
  dimmed?: boolean;
  glowing?: boolean;
  style?: React.CSSProperties;
}

const DIMS = {
  small: { w: 52, h: 76, fs: 14, num: 30, corner: 13, sigil: 26 },
  normal: { w: 92, h: 132, fs: 22, num: 64, corner: 19, sigil: 50 },
  deck: { w: 100, h: 142, fs: 22, num: 68, corner: 20, sigil: 48 },
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
  faceDown = false,
  dimmed = false,
  glowing = false,
  style = {},
}: PlayingCardProps) {
  const dims = DIMS[size];
  const sw = theme.strokeWidth;
  const ink = theme.ink;
  const catalog = useCatalog();

  // The face comes from the backend's catalog. Looked up before anything is
  // drawn, because a passive is a different kind of object from an action card
  // rather than the same card in another colour.
  const action = findAction(catalog, card.defId);
  const passive = action ? undefined : findPassive(catalog, card.defId);
  // Each passive prints in its own ink so the row in front of a player reads as
  // several things rather than one green block. A server that does not send one
  // falls back to the house green every passive used to share.
  const accent = passive
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

  // Face down
  if (faceDown) {
    return (
      <div style={baseStyle}>
        <div
          style={{
            position: "absolute",
            inset: sw,
            background: theme.cardBack,
            borderRadius: 3,
            zIndex: 0,
          }}
        />
        <RoughBox
          width={dims.w}
          height={dims.h}
          stroke={ink}
          strokeWidth={sw}
          roughness={1.8}
        />
        <div
          style={{
            position: "absolute",
            inset: 10,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 2,
          }}
        >
          <svg
            width="70%"
            height="70%"
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            style={{ position: "absolute" }}
          >
            <g
              stroke={ink}
              strokeWidth={sw * 0.55}
              strokeLinecap="round"
              fill="none"
              opacity="0.8"
            >
              <line x1="22" y1="22" x2="78" y2="78" />
              <line x1="78" y1="22" x2="22" y2="78" />
              <line x1="50" y1="16" x2="50" y2="84" />
              <line x1="16" y1="50" x2="84" y2="50" />
            </g>
          </svg>
          <div
            style={{
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.95,
              color: ink,
              fontWeight: 700,
              background: theme.cardBack,
              padding: "2px 6px",
              position: "relative",
              zIndex: 2,
              letterSpacing: "0.04em",
            }}
          >
            Leck ei
          </div>
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
