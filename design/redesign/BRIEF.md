# Yami — Redesign Brief & Design System

**Goal:** a complete *visual/UX* redesign that feels like a new, premium manga product — **same features, same logic, same data/source architecture**. No feature removal. Primary inspiration: Dribbble ref **#12** (coral/light/premium "Mira" comic app), supported by #01/#11 (same family), with cinematic detail heroes (#05/#02), resume‑progress (#10), detail stat cards (#09), and a dark editorial variant (#06/#07/#08).

## Brand
- **Name:** keep **"Yami"** (闇 = "dark" — short, on‑theme, already the package id `me.manga.kira`). Refresh the *wordmark + icon*, not the name. (Rename remains an open option if the owner prefers — costs: store listing, recognition.)
- **Identity spark:** a single warm **coral→amber** accent against a near‑black (dark) / soft‑white (light) canvas. Premium, content‑forward, generous whitespace, soft depth.
- **Tagline direction:** "Read in the dark." / "Your manga, beautifully."

## Color system (maps onto Material‑3 slots in `:ui/theme/YamiColors.kt`)
**Accent (both themes):** `#FF5B6E` → `#FF8A5B` gradient. Solid coral = primary signal (active nav, selected source, primary CTA, favorite). Text‑on‑white coral uses `#D8324A` (WCAG‑AA).

| Slot | Dark | Light |
|---|---|---|
| background | `#0E1014` | `#F5F6F9` |
| surface / card | `#161A23` | `#FFFFFF` |
| surface‑2 | `#1C2130` | `#EAEDF2` |
| text (onSurface) | `#F3F4F7` | `#15171C` |
| muted | `#9AA1AF` | `#5F6B7A` |
| primary | `#FF5B6E` | `#FF5B6E` (text `#D8324A`) |
| outline/hairline | `rgba(255,255,255,.085)` | `rgba(18,20,28,.08)` |
| pure‑black (AMOLED) | bg/surface → `#000000` | n/a |

Dark is the default & "signature" theme (existing users + OLED); light is a first‑class showcase matching ref #12. Pure‑black toggle preserved.

## Typography (keep **Gellix** — already bundled)
Display 28/800 (‑0.6 ls) · Section 19/800 · Title 16/800 · Body 15/600 · Meta 12.5/600 muted · Pill/label 13/700. Bigger, tighter, more confident than current M3 defaults. Arabic/RTL fallback unchanged.

## Shape & spacing
Radii: card **20**, cover **14**, hero **20**, pill **999**, button **14**. 8‑pt grid but more air (screen padding 20, row gap 12). Soft shadows over heavy elevation.

## Component language
- **Circular icon buttons** (42dp, ghost/elevated) for top actions — replace bare M3 IconButtons.
- **Segmented source pills** — selected = coral gradient pill; unselected = ghost. Trailing **edit‑sources** = hairline divider + pencil.
- **Hero/"Popular" cards** — 158×212, rounded 20, full‑bleed cover + bottom scrim, rating pill + optional HOT tag + title/genre overlay.
- **Feed row** — surface card (radius 20): 78×104 cover, title, `source · genre` meta, up to 3 chapter **pills** (lead coral‑soft, older ghost), trailing bookmark button.
- **Floating bottom nav** — capsule, blurred; active tab = coral gradient pill (icon+label), others icon‑only.
- **State views** — keep `YamiLoadingState/Empty/Error/SiteStatus` semantics; restyle to the new system.

## Review gate (per the owner's process)
For every meaningful screen: faithful **old** HTML reconstruction + **new** HTML → render both to PNG (Edge headless, 390×880 @2×) → independent **review subagent panel** (visual / usability+contrast / brand lenses) compares on visual quality, clarity, usability, feature‑completeness, consistency, freshness, and **new ≥ old + visually distinct**. Adopt only on pass. Artifacts live in `design/redesign/` (`*.html`, `renders/*.png`).
**Home v1 gate:** 3/3 `adopt_new` (dark), distinct=yes; fixes folded into v2 (clear edit‑sources + list toggle, AA light contrast, coral budget, chip/nav balance).

## Rollout order (each gated, features/logic untouched)
1. **Home** (foundation) ✅ proof done → implement in `:ui`.
2. Theme tokens + shared components (`YamiColors`, `YamiTypography`, `YamiShapes`, circular button, pills, hero card, feed row, floating nav, state views).
3. Library · Search · **Manga Details** (cinematic hero) · **Reader** (immersive chrome).
4. Downloads · History · Updates · Sources · Statistics.
5. Settings · Onboarding (Welcome/Theme/Language) · About · What's New · Complaints.
6. App icon / wordmark refresh.

## Constraints
Keep features/logic/data/source architecture; do not break config‑backed sources. Implement in rework `:ui` only. Owner‑WIP files untouched except on instruction. Small safe commits; compile 4 targets + tests after meaningful changes.
