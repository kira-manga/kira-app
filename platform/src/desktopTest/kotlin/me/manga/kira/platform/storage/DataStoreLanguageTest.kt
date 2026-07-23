package me.manga.kira.platform.storage

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreLanguageTest {

    private val node: Preferences = Preferences.userRoot().node("me.manga.kira.test.language")
    private lateinit var helper: DataStoreHelper

    @BeforeTest
    fun setUp() {
        node.clear()
        helper = DataStoreHelper(PreferencesSettings(node))
    }

    @AfterTest
    fun tearDown() {
        node.clear()
    }

    @Test
    fun currentLanguageUsesTheSameDefaultAndKeyAsLanguageFlow() {
        assertEquals("", helper.currentLanguage())

        node.put(StorageKeys.SELECTED_LANGUAGE, "ar")

        assertEquals("ar", helper.currentLanguage())
    }
}
