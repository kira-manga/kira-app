package me.manga.kira.ui.welcome

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.get_started
import me.manga.kira.ui.generated.resources.welcome_suptitle
import me.manga.kira.ui.generated.resources.welcome_title
import org.jetbrains.compose.resources.stringResource

/**
 * Architecture-rework Welcome (first-launch) screen — **Phase 7.x.welcome** (Task #306).
 *
 * Stateless `:ui` port of the legacy
 * `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt`. Renders the
 * onboarding chain's step-1 entry: a centered animated-content column with a primary-color
 * title, secondary-color subtitle, and a primary-container Get-Started button that invokes
 * the supplied [onGetStarted] callback to advance to step 2 (Theme picker — rework via §138).
 *
 * **Layer**: `:ui` only. No `:domain`, `:data`, `:presentation`, or Koin surface — Welcome
 * is genuinely stateless (no state, no business logic, one nav callback). Adding empty
 * presentation / domain layers would be premature abstraction.
 *
 * **Visual deltas vs legacy `WelcomeScreen.kt`** (three intentional, all consistent with
 * the rework `:ui` design language settled across §122 / §138):
 *
 *  1. **No `AnimatedBackground` gradient sweep** — matches the §122 (`sources.onboardingfinish`)
 *     and §138 (`theme.swap`) precedent which both intentionally skipped the equivalent
 *     legacy decorative background. Purely cosmetic, no semantic value. The vertical
 *     darkening overlay (`Brush.verticalGradient` from `background.copy(alpha = 0.1f)` to
 *     opaque `background`) is preserved verbatim — it's the legibility shim, not the
 *     decoration.
 *  2. **Localized strings (Phase 11.ui.UP-3a)** — title / subtitle / CTA resolve through
 *     compose-resources `stringResource(Res.string.welcome_title / welcome_suptitle /
 *     get_started)`, reusing the legacy key names so the already-authored en + ar (+ 9 other
 *     locale) translations apply. (Originally inline English literals, deferred to a
 *     "Phase 10 i18n lift"; UP-3 is that lift for the rework `:ui` surfaces.)
 *  3. **Same Material 3 Button styling preserved verbatim** — 52.dp height,
 *     `RoundedCornerShape(26.dp)` clip, primary `containerColor`, `onPrimary` label color.
 *     No visual delta on the CTA itself.
 *
 * **Banned features**: No `!!`, `Any`, `lateinit`, `Thread`. Stateless composable, no
 * concurrency primitives, no nullable-unwrapping.
 *
 * **No load-bearing fix touched**: this file does NOT touch the Coil ImageLoader, the
 * Reader's per-request listener, the Reader's decoder hints, the OkHttp interceptor, or
 * any of the prior load-bearing image-quality posture (Welcome has no images).
 *
 * **Legacy file disposition**: the legacy
 * `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt` is preserved
 * on disk (no longer reachable post-swap — see [me.manga.kira.navigation.routes.
 * WelcomeScreenRoute]). Phase 9.x cleanup retires it alongside other retired legacy
 * screens.
 *
 * @param onGetStarted callback invoked when the user taps the Get-Started button.
 *                     Typically wired to `navController.navigate(Screen.Theme)` at the
 *                     route-adapter layer to advance the onboarding chain.
 *
 * **Audit-trail postscript** (Phase 9.x.welcome.staleKdocSweep.cascade,
 * Task #457, 2026-05-28): two stale citations into the §307-retired legacy
 * `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt`
 * appear above, plus a now-fulfilled prediction:
 *  - Lines 33-34 (preamble paragraph): "Stateless `:ui` port of the legacy
 *    `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt`".
 *  - Line 43 (Visual deltas heading): "Visual deltas vs legacy
 *    `WelcomeScreen.kt`".
 *  - Lines 69-73 (Legacy file disposition paragraph): "the legacy
 *    `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt`
 *    is preserved on disk (no longer reachable post-swap...). Phase 9.x cleanup
 *    retires it alongside other retired legacy screens".
 * The legacy `presentation/features/onboarding/welcome/WelcomeScreen.kt` was
 * retired in Phase 9.x.onboarding.legacy_retire (§307 sweep, commit
 * `6c83364` "delete 5 unreachable legacy onboarding files"); verified by a
 * filesystem check returning zero hits for that path. The forecast made in
 * the disposition paragraph ("Phase 9.x cleanup retires it") has been
 * fulfilled. The three documented visual deltas (no AnimatedBackground,
 * inline literal strings, same Material 3 Button styling) stand on their
 * own merits — the rework `:ui` design language's flat-surface preference,
 * the Phase 10 i18n lift strategy, and the verbatim-preserved CTA chrome
 * are documented inline above and independent of which legacy file
 * originally established the parity precedent. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage; the rework Welcome
 * composable continues to render the onboarding step-1 entry correctly
 * through the legacy retire.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            // No-dep Compose-native animated background (replacement for the legacy Lottie
            // `background.lottie` sweep — lottie-compose is NOT on the :ui classpath, so a new
            // Gradle dep would be out of edits-only scope). A slow infinite-transition drives a
            // diagonal primary→secondary→background gradient whose start/end points pan across
            // the screen, giving Welcome subtle motion. If lottie-compose is later added to
            // :ui, the asset is available at `ui/.../composeResources/files/background.lottie`
            // and this Box can be swapped for `LottieAnimation(rememberLottieComposition { ... })`.
            val transition = rememberInfiniteTransition(label = "welcomeBackground")
            val sweep by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 9000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "welcomeBackgroundSweep",
            )
            val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            val secondary = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
            val base = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // `sweep` is read in the draw phase only, so the animation never invalidates
                        // composition — mirrors SourcesScreen's AnimatedSourcesBackground.
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(primary, secondary, base),
                                start = Offset(0f, 1500f * sweep),
                                end = Offset(1500f * (1f - sweep), 1500f),
                            ),
                        )
                    },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .animateContentSize(animationSpec = tween(600)),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.welcome_title),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Text(
                    text = stringResource(Res.string.welcome_suptitle),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onGetStarted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = stringResource(Res.string.get_started),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
    }
}
