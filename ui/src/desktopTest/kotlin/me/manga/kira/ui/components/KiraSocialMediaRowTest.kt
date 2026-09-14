package me.manga.kira.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.ui.theme.KiraTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KiraSocialMediaRowTest {
    @Test
    fun everyEnabledChannelOpensItsDestinationAndUnavailableDiscordIsAbsent() =
        runComposeUiTest {
            val openedUrls = mutableListOf<String>()
            val expected =
                listOf(
                    "Twitter" to "https://twitter.com/yami_manga_me",
                    "Facebook" to "https://www.facebook.com/61577403584218",
                    "Instagram" to "https://www.instagram.com/yami_manga_me",
                    "WhatsApp" to "https://api.whatsapp.com/send?phone=201558657735&text=Hey%20from%20Kira!",
                    "Website" to "https://kiramanga.me",
                )
            setContent {
                KiraTheme(darkTheme = false) {
                    KiraSocialMediaRow(onOpenUrl = { openedUrls += it })
                }
            }

            onNodeWithContentDescription("Discord").assertDoesNotExist()
            val buttons = onAllNodes(hasClickAction() and isEnabled())
            buttons.assertCountEquals(expected.size)
            runOnIdle { assertEquals(emptyList(), openedUrls) }
            expected.forEachIndexed { index, (label, _) ->
                buttons[index].assertContentDescriptionEquals(label).performClick()
                runOnIdle {
                    assertEquals(expected.take(index + 1).map { it.second }, openedUrls)
                }
            }
        }
}
