package me.manga.kira.ui.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcherOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.LocalDate
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.updates.UpdatesEffect
import me.manga.kira.presentation.updates.UpdatesIntent
import me.manga.kira.presentation.updates.UpdatesState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.chapter_number
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.details_mark_read
import me.manga.kira.ui.generated.resources.details_mark_unread
import me.manga.kira.ui.generated.resources.details_more_options
import me.manga.kira.ui.generated.resources.download
import me.manga.kira.ui.generated.resources.downloaded
import me.manga.kira.ui.generated.resources.undo
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val UPDATES_PHONE_WIDTH = 320
internal const val UPDATES_PHONE_HEIGHT = 640
internal const val UPDATES_MIN_TARGET = 48f
internal const val UPDATES_SETTLE_MILLIS = 1_000L
private const val MAX_FOCUS_STEPS = 24

// Synthetic local-only cover paths: image decoding and persistence are not under test.
internal val unreadUpdate =
    UpdateEntry(
        id = 1L,
        api = "accessibility-fixture",
        language = "en",
        mangaId = 1L,
        mangaTitle = "A shared manga title long enough to ellipsize on a narrow phone",
        mangaImageUrl = "file:///updates-accessibility-fixture/cover.png",
        mangaUrl = "file:///updates-accessibility-fixture/manga",
        chapterId = 1L,
        chapterNumber = "1",
        chapterUrl = "file:///updates-accessibility-fixture/chapter-1",
        notificationDate = LocalDate.parse("2026-09-01"),
        isRead = false,
        isDownloaded = false,
        localImagePaths = emptyList(),
    )

internal val readUpdate =
    unreadUpdate.copy(
        id = 2L,
        chapterId = 2L,
        chapterNumber = "2",
        chapterUrl = "file:///updates-accessibility-fixture/chapter-2",
        isRead = true,
        isDownloaded = true,
        localImagePaths = listOf("/updates-accessibility-fixture/chapter-2.png"),
    )

@OptIn(ExperimentalTestApi::class)
internal fun runUpdatesAccessibilityTest(
    fontScale: Float = 1f,
    block: suspend ComposeUiTest.() -> Unit,
) = runSkikoComposeUiTest(
    size = Size(UPDATES_PHONE_WIDTH.toFloat(), UPDATES_PHONE_HEIGHT.toFloat()),
    density = Density(density = 1f, fontScale = fontScale),
) {
    block()
}

@OptIn(ExperimentalTestApi::class)
internal class UpdatesRowAccessibilityFixture(
    private val ui: ComposeUiTest,
) {
    var state by mutableStateOf(UpdatesState(isLoading = false, items = listOf(unreadUpdate, readUpdate)))
    val effects = MutableSharedFlow<UpdatesEffect>(extraBufferCapacity = 1)
    val keyboard = UpdatesKeyboardDriver(ui)
    private val intents = mutableListOf<UpdatesIntent>()
    private val navigation = mutableListOf<String>()
    private var labels = emptyMap<StringResource, String>()
    private var chapters = emptyMap<String, String>()
    private val labelKeys =
        listOf(
            Res.string.delete,
            Res.string.details_mark_read,
            Res.string.details_mark_unread,
            Res.string.details_more_options,
            Res.string.download,
            Res.string.downloaded,
            Res.string.undo,
        )

    fun render(direction: LayoutDirection = LayoutDirection.Ltr) {
        ui.setContent {
            bindKeyboardToHost(keyboard)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KiraTheme(darkTheme = false) {
                    labels = labelKeys.associateWith { stringResource(it) }
                    chapters =
                        state.items.associate {
                            it.chapterNumber to stringResource(Res.string.chapter_number, it.chapterNumber)
                        }
                    UpdatesScreenContent(
                        state = state,
                        effects = effects,
                        onIntent = { intents += it },
                        onNavigateToDetails = { navigation += "details" },
                        onNavigateToReader = { navigation += "reader" },
                    )
                }
            }
        }
        ui.waitForIdle()
        keyboard.captureHostRoot()
    }

    fun label(key: StringResource): String = labels.getValue(key)

    fun rowMatcher(entry: UpdateEntry): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and
            hasText(chapters.getValue(entry.chapterNumber))

    fun row(entry: UpdateEntry): SemanticsNodeInteraction = ui.onNode(rowMatcher(entry))

    fun control(
        entry: UpdateEntry,
        key: StringResource,
    ): SemanticsNodeInteraction = ui.onNode(hasContentDescription(label(key)) and hasAnyAncestor(rowMatcher(entry)))

    fun cover(entry: UpdateEntry): SemanticsNodeInteraction =
        ui.onNode(hasContentDescription(entry.mangaTitle) and hasAnyAncestor(rowMatcher(entry)))

    fun menu(key: StringResource) = ui.onNode(hasText(label(key)) and hasAnyAncestor(isPopup()))

    fun assertRowActions(entry: UpdateEntry) {
        val node = row(entry).assertHasClickAction().assert(hasText(entry.mangaTitle)).fetchSemanticsNode()
        val readKey = if (entry.isRead) Res.string.details_mark_unread else Res.string.details_mark_read
        assertEquals(
            listOf(label(readKey), label(Res.string.delete)),
            node.config[SemanticsActions.CustomActions].map { it.label },
        )
    }

    fun invokeCustom(
        entry: UpdateEntry,
        key: StringResource,
    ) {
        val action =
            row(entry).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label(key) }
        ui.runOnIdle { assertTrue(action.action()) }
    }

    fun expectOnly(vararg expected: UpdatesIntent) {
        ui.runOnIdle {
            assertEquals(expected.toList(), intents)
            assertTrue(navigation.isEmpty(), "No navigation effect was supplied")
            intents.clear()
        }
    }
}

@OptIn(ExperimentalTestApi::class)
internal class UpdatesKeyboardDriver(
    private val ui: ComposeUiTest,
) {
    private var backInput: DirectNavigationEventInput? = null
    private lateinit var hostRoot: RootForTest

    fun attach(owner: NavigationEventDispatcherOwner) {
        check(backInput == null)
        val input = DirectNavigationEventInput()
        owner.navigationEventDispatcher.addInput(input)
        backInput = input
    }

    fun detach(owner: NavigationEventDispatcherOwner) {
        owner.navigationEventDispatcher.removeInput(checkNotNull(backInput))
        backInput = null
    }

    fun captureHostRoot() {
        hostRoot = checkNotNull(ui.onRoot().fetchSemanticsNode().root)
    }

    // UI 1.11.1's real KeyEvent factory is opted into only for this test-host adapter.
    @OptIn(InternalComposeUiApi::class)
    fun press(key: Key): Boolean =
        ui.runOnIdle {
            val consumed = hostRoot.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            if (!consumed && key == Key.Escape) checkNotNull(backInput).backCompleted()
            // The main host root survives popup dismissal; never reuse a disposed popup root.
            hostRoot.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            consumed
        }

    fun tabTo(target: SemanticsNodeInteraction) {
        traverse(target, Key.Tab, ui.onRoot())
    }

    fun open(
        target: SemanticsNodeInteraction,
        key: Key,
    ) {
        tabTo(target)
        press(key)
    }

    fun select(
        target: SemanticsNodeInteraction,
        key: Key,
    ): Boolean {
        traverse(target, Key.DirectionDown, ui.onNode(isPopup()))
        return press(key)
    }

    private fun traverse(
        target: SemanticsNodeInteraction,
        key: Key,
        root: SemanticsNodeInteraction,
    ) {
        repeat(MAX_FOCUS_STEPS) {
            root.performKeyInput { pressKey(key) }
            if (target.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true) {
                target.assertIsFocused()
                return
            }
        }
        target.assertIsFocused()
    }
}

@Composable
private fun bindKeyboardToHost(keyboard: UpdatesKeyboardDriver) {
    // Pinned Skiko host supplies this same owner to Popup's navigation handler.
    val owner =
        checkNotNull(LocalLifecycleOwner.current as? NavigationEventDispatcherOwner) {
            "Updates keyboard fixture requires the pinned Skiko navigation owner"
        }
    DisposableEffect(owner) {
        keyboard.attach(owner)
        onDispose { keyboard.detach(owner) }
    }
}
