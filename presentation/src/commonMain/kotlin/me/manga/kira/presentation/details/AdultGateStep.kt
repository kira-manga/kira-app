package me.manga.kira.presentation.details

/**
 * Multi-step hard-block gate for adult/+18 manga (P0-ADULT, native parity).
 *
 * Direct port of the native `presentation/features/details/domain/DialogState` enum
 * (`{ AdultWarning, MStep1, MStep2, None }`). For an adult manga the Details screen chains three
 * dialog states and EVERY path ends by navigating back — the content (cover / chapters) is NEVER
 * revealed. This is a legal/compliance block (Google Play policy + cultural-values respect), not a
 * "confirm you're 18 then show it" age-gate.
 *
 * Transition chain (mirrors native `MangaDetailsScreen` lines 74-128):
 *  - [AdultWarning]: the "Content unavailable" dialog with the red `ic_pluss18` icon + Play-policy
 *    apology. Native wires BOTH its confirm ("Close") and dismiss ("Cancel") buttons to the same
 *    back-navigation; advancing to [MStep1] is the documented next step in the chain. Either button
 *    or an outside-tap dismiss ⇒ back-navigate.
 *  - [MStep1]: first meme dialog (`MConfirmationDialog`, `showContinue = true`). Continue ⇒ advance
 *    to [MStep2]; Close / dismiss ⇒ back-navigate.
 *  - [MStep2]: second meme dialog (`MConfirmationDialog`, `showContinue = false` — no Continue
 *    button). Close / dismiss ⇒ back-navigate.
 *  - [None]: no gate active. For a non-adult manga the gate starts here and the body renders
 *    normally. An adult manga can NEVER reach [None] from a gate step — every exit is a
 *    back-navigation, so the body is never shown.
 *
 * Held on [DetailsState.adultGateStep]; driven by the `OnAdult*` intents (see [DetailsIntent]).
 * The VM initializes it to [AdultWarning] the moment the active manga classifies as adult (see
 * [DetailsViewModel]); a non-adult manga keeps the default [None].
 */
enum class AdultGateStep { AdultWarning, MStep1, MStep2, None }
