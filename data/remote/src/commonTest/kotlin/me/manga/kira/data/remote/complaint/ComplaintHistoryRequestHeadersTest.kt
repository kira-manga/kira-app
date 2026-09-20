package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplaintHistoryRequestHeadersTest {
    @Test
    fun exactlyOneCompactBearerWithIdentityEncodingIsRequired() {
        val valid = "Bearer synthetic.header.signature"
        assertTrue(ComplaintHistoryRequestHeaders.accepts(listOf(valid), listOf("identity")))
        listOf(
            emptyList(),
            listOf(valid, valid),
            listOf("Basic synthetic"),
            listOf("bearer a.b.c"),
            listOf("Bearer a.b"),
            listOf("Bearer a.b.c.d"),
            listOf("Bearer a.b.c, Bearer a.b.c"),
            listOf("Bearer a.b.c="),
            listOf("Bearer a.b.c\n"),
            listOf("Bearer a.b.é"),
        ).forEach { assertFalse(ComplaintHistoryRequestHeaders.accepts(it, listOf("identity"))) }
        listOf(emptyList(), listOf("gzip"), listOf("identity", "identity"), listOf("identity, identity"))
            .forEach { assertFalse(ComplaintHistoryRequestHeaders.accepts(listOf(valid), it)) }
    }

    @Test
    fun authorizationUsesTheExistingFourKibibyteHeaderCeilingWithoutDecodingClaims() {
        val prefix = "Bearer a.b."
        val exact = prefix + "c".repeat(AUTHORIZATION_CHARACTERS - prefix.length)
        assertTrue(ComplaintHistoryRequestHeaders.accepts(listOf(exact), listOf("identity")))
        assertFalse(ComplaintHistoryRequestHeaders.accepts(listOf(exact + "c"), listOf("identity")))
    }

    private companion object {
        const val AUTHORIZATION_CHARACTERS = 4 * 1_024
    }
}
