package me.manga.kira.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B6 (#1) — host-swap helper [replaceBaseUrl]. KMP-portable reimplementation of native's
 * java.net.URI-based `replaceBaseUrl`; must swap scheme+host, preserve path/query/fragment, use
 * only the ORIGIN of the new base (its own path dropped — 2026-07 audit: appending a path-bearing
 * base doubled the path), and return malformed / scheme-less input unchanged.
 */
class ReplaceBaseUrlTest {
    @Test
    fun swapsHost_preservesPath() {
        assertEquals(
            "https://new.example/manga/123",
            replaceBaseUrl("https://old.example/manga/123", "https://new.example"),
        )
    }

    @Test
    fun preservesQueryAndFragment() {
        assertEquals(
            "https://new.example/p?a=1&b=2#frag",
            replaceBaseUrl("https://old.example/p?a=1&b=2#frag", "https://new.example"),
        )
    }

    @Test
    fun trimsTrailingSlashOnNewBase() {
        assertEquals(
            "https://new.example/p",
            replaceBaseUrl("https://old.example/p", "https://new.example/"),
        )
    }

    @Test
    fun noPath_yieldsBareNewBase() {
        assertEquals(
            "https://new.example",
            replaceBaseUrl("https://old.example", "https://new.example"),
        )
    }

    @Test
    fun swapsPortToo_partOfAuthority() {
        assertEquals(
            "https://new.example/p",
            replaceBaseUrl("https://old.example:8443/p", "https://new.example"),
        )
    }

    @Test
    fun schemeChangeIsCarriedByNewBase() {
        assertEquals(
            "http://new.example/p",
            replaceBaseUrl("https://old.example/p", "http://new.example"),
        )
    }

    @Test
    fun noScheme_returnedUnchanged() {
        assertEquals("/relative/path", replaceBaseUrl("/relative/path", "https://new.example"))
        assertEquals("", replaceBaseUrl("", "https://new.example"))
    }

    // --- 2026-07 audit: a path-bearing new base must contribute only its origin --------------------

    @Test
    fun pathBearingNewBase_noPathDoubling() {
        // SwatManga shape: the base carries /v2/api/v1 and every stored URL already embeds it. The
        // old append-full-base behavior produced …/v2/api/v1/v2/api/v1/7.
        assertEquals(
            "https://new.example/v2/api/v1/series/7",
            replaceBaseUrl("https://old.example/v2/api/v1/series/7", "https://new.example/v2/api/v1"),
        )
    }

    @Test
    fun pathBearingNewBase_queryAndPortVariants() {
        assertEquals(
            "https://new.example/p?a=1",
            replaceBaseUrl("https://old.example/p?a=1", "https://new.example/base/"),
        )
        assertEquals(
            "https://new.example:8443/p",
            replaceBaseUrl("https://old.example/p", "https://new.example:8443/base"),
        )
    }
}
