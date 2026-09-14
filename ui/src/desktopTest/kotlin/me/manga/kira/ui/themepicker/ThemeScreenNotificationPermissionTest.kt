package me.manga.kira.ui.themepicker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.presentation.theme.ThemeState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.continue_string
import me.manga.kira.ui.generated.resources.grant_permission
import me.manga.kira.ui.generated.resources.notification_permission_optional
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ThemeScreenNotificationPermissionTest {

    @Test
    fun requiredPermission_blocksContinueWhenDenied() = runComposeUiTest {
        var continueLabel = ""
        var continueTaps = 0
        setContent {
            KiraTheme(darkTheme = false) {
                continueLabel = stringResource(Res.string.continue_string)
                ThemeScreenContent(
                    state = ThemeState(isLoading = false),
                    onIntent = {},
                    onContinue = { continueTaps++ },
                    hasNotificationPermission = false,
                    isNotificationPermissionRequired = true,
                    onRequestNotificationPermission = {},
                )
            }
        }

        onNodeWithText(continueLabel).assertIsNotEnabled().performClick()
        assertEquals(0, continueTaps)
    }

    @Test
    fun optionalPermission_keepsContinueEnabledAndUsesOptionalCopy() = runComposeUiTest {
        var continueLabel = ""
        var optionalCopy = ""
        var continueTaps = 0
        setContent {
            KiraTheme(darkTheme = false) {
                continueLabel = stringResource(Res.string.continue_string)
                optionalCopy = stringResource(Res.string.notification_permission_optional)
                ThemeScreenContent(
                    state = ThemeState(isLoading = false),
                    onIntent = {},
                    onContinue = { continueTaps++ },
                    hasNotificationPermission = false,
                    isNotificationPermissionRequired = false,
                    onRequestNotificationPermission = {},
                )
            }
        }

        onNodeWithText(optionalCopy).assertIsDisplayed()
        onNodeWithText(continueLabel).assertIsEnabled().performClick()
        assertEquals(1, continueTaps)
    }

    @Test
    fun optionalPermission_requestRunsOnlyAfterGrantTap() = runComposeUiTest {
        var grantLabel = ""
        var requestTaps = 0
        setContent {
            KiraTheme(darkTheme = false) {
                grantLabel = stringResource(Res.string.grant_permission)
                ThemeScreenContent(
                    state = ThemeState(isLoading = false),
                    onIntent = {},
                    onContinue = {},
                    hasNotificationPermission = false,
                    isNotificationPermissionRequired = false,
                    onRequestNotificationPermission = { requestTaps++ },
                )
            }
        }

        assertEquals(0, requestTaps)
        onNodeWithText(grantLabel).performClick()
        assertEquals(1, requestTaps)
    }
}
