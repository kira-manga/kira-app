package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class InstallationDeletionResponseTest {
    @Test
    fun genericMixedWrongAndMalformedGoneProblemsNeverAuthorizeCleanup() =
        runTest {
            val terminal = mutationProblem(HttpStatusCode.Gone, "INSTALLATION_DELETED")
            val mixed = terminal.replace(
                "]}",
                """,{"code":"FORBIDDEN","message":"Synthetic refusal."}]}""",
            )
            listOf(
                mutationProblem(HttpStatusCode.Gone, "NOT_FOUND"),
                """{"type":"about:blank","title":"Gone","status":410}""",
                mixed,
                terminal.replace("\"Gone\"", "\"Not Found\""),
                terminal.replace("\"errors\":", "\"detail\":\"Synthetic\",\"errors\":"),
                terminal.replace("\"message\":", "\"path\":\"synthetic\",\"message\":"),
                terminal.replace("\"status\":410", "\"status\":404"),
                terminal.replace("INSTALLATION_DELETED", "UNKNOWN_CODE"),
                terminal.replace("\"status\":410", "\"status\":410,\"status\":410"),
            ).forEach { deletionResponseRetains(HttpStatusCode.Gone, ByteReadChannel(it.encodeToByteArray())) }
        }

    @Test
    fun acceptedRequiresExactlyOneBoundedDecimalRetryAfterAndAnActuallyEmptyBody() =
        runTest {
            val values = listOf(
                emptyList(), listOf("0"), listOf("61"), listOf("-1"), listOf("+1"), listOf("1.0"),
                listOf("Thu, 17 Sep 2026 08:09:10 GMT"), listOf("1", "1"), listOf("1,1"), listOf("9".repeat(129)),
            )
            for (retry in values) {
                val headers = deletionHeaders(HttpStatusCode.Accepted) {
                    remove(HttpHeaders.RetryAfter)
                    retry.forEach { append(HttpHeaders.RetryAfter, it) }
                }
                assertIs<AppError.Network.Serialization>(
                    deletionResponseRetains(HttpStatusCode.Accepted, ByteReadChannel(byteArrayOf()), headers),
                )
            }
            listOf(HttpStatusCode.Accepted, HttpStatusCode.NoContent).forEach { status ->
                assertIs<AppError.Network.Serialization>(deletionResponseRetains(status, ByteReadChannel(byteArrayOf(32))))
            }
        }

    @Test
    fun wrongContractCacheMediaEncodingOrFramingCannotTurnAnEmptyBodyIntoTerminalAuthority() =
        runTest {
            for ((name, value) in invalidDeletionHeaders()) {
                val headers = deletionHeaders {
                    remove(name)
                    append(name, value)
                }
                assertIs<AppError.Network.Serialization>(
                    deletionResponseRetains(HttpStatusCode.NoContent, ByteReadChannel(byteArrayOf()), headers),
                )
            }
            val duplicate = deletionHeaders { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") }
            deletionResponseRetains(HttpStatusCode.NoContent, ByteReadChannel(byteArrayOf()), duplicate)
            val framing = deletionHeaders {
                append(HttpHeaders.ContentLength, "0")
                append(HttpHeaders.TransferEncoding, "chunked")
            }
            deletionResponseRetains(HttpStatusCode.NoContent, ByteReadChannel(byteArrayOf()), framing)
        }

    @Test
    fun exactProblemLimitIsAllowedButLimitPlusOneAndInvalidUtf8AreRefusedBeforeParsing() =
        runTest {
            val status = HttpStatusCode.ServiceUnavailable
            val problem = mutationProblem(status, "SERVICE_UNAVAILABLE")
            for (extra in 0..1) {
                val bytes = (problem + " ".repeat(Policy.MAX_PROBLEM_BYTES + extra - problem.length)).encodeToByteArray()
                val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
                val error = deletionResponseRetains(status, channel)
                if (extra == 0) assertIs<AppError.Network.Http>(error) else assertIs<AppError.Network.Serialization>(error)
                assertTrue(channel.cancelled)
            }
            assertIs<AppError.Network.Serialization>(deletionResponseRetains(status, ByteReadChannel(byteArrayOf(-61, 40))))
        }

    @Test
    fun redirectsAndUndeclaredSuccessfulShapesAreNotFollowedOrTreatedAsCompletion() =
        runTest {
            listOf(HttpStatusCode.MovedPermanently, HttpStatusCode.TemporaryRedirect, HttpStatusCode.OK, HttpStatusCode.Created)
                .forEach { status ->
                    assertIs<AppError.Network.Serialization>(deletionResponseRetains(status, ByteReadChannel(byteArrayOf())))
                }
        }
}

private fun invalidDeletionHeaders(): List<Pair<String, String>> =
    listOf(
        ComplaintBoundedResponse.CONTRACT_HEADER to "2",
        HttpHeaders.CacheControl to "no-store",
        HttpHeaders.ContentType to "application/problem+json",
        HttpHeaders.ContentEncoding to "gzip",
        HttpHeaders.ContentLength to "1",
        HttpHeaders.ContentLength to "0,0",
        HttpHeaders.TransferEncoding to "gzip",
        HttpHeaders.Location to "https://elsewhere.invalid/",
        HttpHeaders.ETag to "\"synthetic\"",
        HttpHeaders.WWWAuthenticate to "Bearer realm=\"synthetic\"",
    )
