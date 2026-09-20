package me.manga.kira.data.identity

import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class WorkIdentityPolicyTest {
    private val token = AcceptedCatalogToken(1, SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, 7, "a".repeat(64)), "c".repeat(64))
    private val accepted = rule()
    private val policy = policy(listOf(accepted))
    private val resolver = WorkOwnerResolver(policy)
    private val current = work("https://current.test/work/one")

    @Test
    fun aliasesFoldOnlyDeclaredOriginsAndDefaultPorts() {
        val pairs = listOf(
            "https://previous.test/work/one" to current.url,
            "HTTPS://PREVIOUS.TEST:443/work/one" to current.url,
            "https://previous.test:00443/work/one" to current.url,
            "https://CURRENT.TEST:443/work/one" to current.url,
            "https://older.test/work/one" to "https://previous.test/work/one",
            "https://previous.test?b=2&a=%2f#A" to "https://current.test?b=2&a=%2f#A",
        )
        val http = policy(listOf(rule(base = "http://current.test")))
        assertEquals(WorkAliasComparison.DeclaredAlias, http.compare(work("http://previous.test:80/work/one"), work("http://current.test/work/one")))
        pairs.forEach { (left, right) ->
            assertEquals(WorkAliasComparison.DeclaredAlias, policy.compare(work(left), work(right)))
            assertEquals(WorkAliasComparison.DeclaredAlias, policy.compare(work(right), work(left)))
        }
    }

    @Test
    fun suffixAndSchemeDifferencesRemainDistinct() {
        val suffixes = listOf(
            "" to "/",
            "/A" to "/a",
            "/%2f" to "/%2F",
            "/a%2fb" to "/a/b",
            "/a?x=1&y=2" to "/a?y=2&x=1",
            "/a#one" to "/a",
            "/a#one" to "/a#two",
        )
        suffixes.forEach { (left, right) ->
            assertEquals(
                WorkAliasComparison.Distinct,
                policy.compare(work("https://previous.test$left"), work("https://current.test$right")),
            )
        }
        assertEquals(
            WorkAliasComparison.Distinct,
            policy.compare(work("http://previous.test/work/one"), current),
        )
        assertEquals(WorkAliasComparison.Distinct, policy.compare(current, current.copy(api = "other")))
    }

    @Test
    fun malformedAndUndeclaredRequestsNeverBecomeMissing() {
        unsupportedUrls().forEach { (url, reason) ->
            val request = work(url)
            assertEquals(WorkAliasComparison.Rejected(reason), policy.compare(request, current))
            val result = assertIs<WorkOwnerResolution.Conflict>(resolver.resolve(request, null, emptyList()))
            assertEquals(WorkOwnerConflict.RejectedRequest(reason), result.reason)
        }
        val blankApi = current.copy(api = "")
        val result = assertIs<WorkOwnerResolution.Conflict>(resolver.resolve(blankApi, null, emptyList()))
        assertEquals(WorkOwnerConflict.RejectedRequest(WorkAliasRejection.InvalidApi), result.reason)
    }

    @Test
    fun exactOwnersRemainRecoverableWithoutUsableAliasRules() {
        val offlinePolicy = policy(emptyList())
        val offline = WorkOwnerResolver(offlinePolicy)
        listOf("not a URL", "ftp://mirror.test/work/one", "https://mirror.test/work/one").forEach { raw ->
            val owner = SavedWorkIdentity(1, work(raw))
            assertEquals(WorkAliasComparison.Exact, offlinePolicy.compare(owner.locator, owner.locator))
            val found = assertIs<WorkOwnerResolution.Found>(offline.resolve(owner.locator, owner, listOf(owner)))
            assertEquals(owner, found.owner)
            assertIs<WorkOwnerResolution.Conflict>(offline.resolve(owner.locator, null, emptyList()))
            assertIs<WorkOwnerResolution.Found>(offline.resolveRetained(owner, owner, listOf(owner)))
        }
    }

    @Test
    fun rejectedAndAmbiguousRulesNeverAuthorizeAnUnownedRequest() {
        val cases = listOf(
            emptyList<AcceptedSourceAliasRule>() to WorkAliasRejection.MissingAcceptedRule,
            listOf(accepted, rule(base = "https://other.test")) to WorkAliasRejection.AmbiguousAcceptedRule,
            listOf(rule(base = "https://current.test/base")) to WorkAliasRejection.NonRootBaseUrl,
            listOf(rule(base = "https://current.test?lang=en")) to WorkAliasRejection.NonRootBaseUrl,
            listOf(rule(base = "https://user@current.test")) to WorkAliasRejection.InvalidAcceptedBaseUrl,
        )
        cases.forEach { (rules, reason) ->
            val scoped = WorkOwnerResolver(policy(rules))
            val result = assertIs<WorkOwnerResolution.Conflict>(scoped.resolve(current, null, emptyList()))
            assertEquals(WorkOwnerConflict.RejectedRequest(reason), result.reason)
            val exact = SavedWorkIdentity(1, current)
            assertIs<WorkOwnerResolution.Found>(scoped.resolve(current, exact, listOf(exact)))
        }
    }

    @Test
    fun capturedPolicyIgnoresLaterInputMutationAndTokenIncludesChecksum() {
        val previous = mutableListOf("previous.test")
        val rules = mutableListOf(rule(previous = previous))
        val captured = policy(rules)
        previous.clear()
        previous += "mirror.test"
        rules.clear()
        rules += rule(base = "https://replacement.test")
        assertEquals(
            WorkAliasComparison.DeclaredAlias,
            captured.compare(work("https://previous.test/work/one"), current),
        )
        assertEquals(
            WorkAliasComparison.Rejected(WorkAliasRejection.UndeclaredPageHost),
            captured.compare(work("https://mirror.test/work/one"), current),
        )
        assertEquals(token, captured.token)
        assertNotEquals(token, token.copy(payloadDigest = "b".repeat(64)))
        assertNotEquals(token, token.copy(identity = token.identity.copy(checksum = "b".repeat(64))))
    }

    @Test
    fun multipleAuthorizedOwnersConflictEvenWithAnExactOrRetainedOwner() {
        val exact = SavedWorkIdentity(1, current)
        val alias = SavedWorkIdentity(2, work("https://previous.test/work/one"))
        val distinct = SavedWorkIdentity(3, work("https://current.test/work/two"))
        val candidates = listOf(distinct, alias, exact, alias)
        val conflict = assertIs<WorkOwnerResolution.Conflict>(resolver.resolve(current, exact, candidates))
        assertEquals(WorkOwnerConflict.MultipleAuthorizedOwners, conflict.reason)
        assertEquals(setOf(exact, alias), conflict.owners.toSet())
        assertEquals(conflict, resolver.resolveRetained(exact, exact, candidates))
    }

    @Test
    fun globalExactUrlOccupancyCannotAttachToAnotherApi() {
        val otherApi = SavedWorkIdentity(1, current.copy(api = "other"))
        val alias = SavedWorkIdentity(2, work("https://previous.test/work/one"))
        val conflict = assertIs<WorkOwnerResolution.Conflict>(resolver.resolve(current, otherApi, listOf(alias)))
        assertEquals(WorkOwnerConflict.CrossApiExactUrl, conflict.reason)
        assertEquals(listOf(otherApi), conflict.owners)
    }

    @Test
    fun excludedRowsDoNotBlockUnrelatedFoundOrMissingButCannotBeRewritten() {
        val alias = SavedWorkIdentity(1, work("https://previous.test/work/one"))
        val mirror = SavedWorkIdentity(2, work("https://mirror.test/work/one"))
        val malformed = SavedWorkIdentity(3, work("broken URL"))
        val candidates = listOf(alias, mirror, malformed)
        val expected = listOf(
            ExcludedWorkOwner(mirror, WorkAliasRejection.UndeclaredPageHost),
            ExcludedWorkOwner(malformed, WorkAliasRejection.MalformedUrl),
        )
        val found = assertIs<WorkOwnerResolution.Found>(resolver.resolve(current, null, candidates))
        assertEquals(alias, found.owner)
        assertEquals(expected, found.excluded)
        val missing = assertIs<WorkOwnerResolution.Missing>(
            resolver.resolve(work("https://current.test/work/two"), null, candidates),
        )
        assertEquals(expected, missing.excluded)
        assertIs<WorkOwnerResolution.Found>(resolver.resolve(mirror.locator, mirror, candidates))
        assertIs<WorkAliasComparison.Rejected>(policy.compare(mirror.locator, current))
        assertEquals(listOf(alias, mirror, malformed), candidates)
    }

    @Test
    fun retainedIdsAllowProvenHostMoveButNeverReplacementOrInsertion() {
        val retained = SavedWorkIdentity(1, work("https://previous.test/work/one"))
        val moved = retained.copy(locator = current)
        val found = assertIs<WorkOwnerResolution.Found>(resolver.resolveRetained(retained, null, listOf(moved)))
        assertEquals(moved, found.owner)
        val changed = assertIs<WorkOwnerResolution.Conflict>(
            resolver.resolveRetained(retained, null, listOf(moved.copy(id = 2))),
        )
        assertEquals(WorkOwnerConflict.RetainedOwnerChanged, changed.reason)
        val absent = assertIs<WorkOwnerResolution.Conflict>(resolver.resolveRetained(retained, null, emptyList()))
        assertEquals(WorkOwnerConflict.RetainedOwnerMissing, absent.reason)
    }

    @Test
    fun contradictoryCandidateReadsFailClosedInsteadOfMaskingAnOwner() {
        val exact = SavedWorkIdentity(1, current)
        val otherUrl = exact.copy(locator = work("https://current.test/work/two"))
        val otherApi = exact.copy(id = 2, locator = current.copy(api = "other"))
        val cases = listOf(
            null to listOf(exact),
            exact to emptyList(),
            otherUrl to listOf(exact),
            exact to listOf(exact, otherUrl),
            exact to listOf(exact, otherApi),
        )
        cases.forEach { (global, candidates) ->
            val result = assertIs<WorkOwnerResolution.Conflict>(resolver.resolve(current, global, candidates))
            assertEquals(WorkOwnerConflict.InconsistentCandidates, result.reason)
        }
        assertIs<WorkOwnerResolution.Found>(resolver.resolve(current, exact, listOf(exact, exact)))
    }

    @Test
    fun locatorsAreLosslessAndProgressFencesAreExplicit() {
        val raw = work(" https://current.test/Case?b=2&a=%2F#Fragment ")
        assertNotEquals(current, raw)
        assertEquals(" https://current.test/Case?b=2&a=%2F#Fragment ", raw.url)
        val owner = SavedProgressOwner(SavedWorkIdentity(1, raw))
        val chapter = ChapterLocator(raw, "raw chapter URL")
        val handle = ProgressHandle(chapter, 0, 0, owner)
        assertNotEquals(ProgressSnapshot(handle, null), ProgressSnapshot(handle, 0))
        assertNotEquals(chapter, chapter.copy(work = raw.copy(api = "other")))
        assertFailsWith<IllegalArgumentException> { SavedWorkIdentity(0, raw) }
        assertFailsWith<IllegalArgumentException> { owner.copy(chapterId = 0) }
        assertFailsWith<IllegalArgumentException> { handle.copy(workGeneration = -1) }
        assertFailsWith<IllegalArgumentException> { handle.copy(chapterGeneration = -1) }
        assertFailsWith<IllegalArgumentException> { ProgressSnapshot(handle, -1) }
    }

    @Test
    fun currentOriginCreationDoesNotGuessUnknownOrIncompatibleOldOwners() {
        val unknown = AcceptedSourceAliasRule("source", "https://current.test", listOf(
            SelectedPreviousHost("previous.test", PreviousHostAuthority.UNKNOWN, null),
        ))
        val old = SavedWorkIdentity(1, work("https://previous.test/work/one"))
        val scoped = WorkOwnerResolver(policy(listOf(unknown)))
        val missing = assertIs<WorkOwnerResolution.Missing>(scoped.resolve(current, null, listOf(old)))
        assertEquals(listOf(old), missing.excluded.map { it.owner })
        assertIs<WorkOwnerResolution.Found>(scoped.resolve(old.locator, old, listOf(old)))
        assertIs<WorkOwnerResolution.Conflict>(scoped.resolve(old.locator, null, emptyList()))
        assertIs<WorkOwnerResolution.Conflict>(scoped.resolve(work("http://current.test/work/one"), null, emptyList()))
        val changed = WorkOwnerResolver(policy(listOf(rule(incompatible = true))))
        assertIs<WorkOwnerResolution.Missing>(changed.resolve(current, null, listOf(old)))
        assertIs<WorkOwnerResolution.Conflict>(changed.resolve(old.locator, null, emptyList()))
    }

    private fun unsupportedUrls() = listOf(
        "ftp://current.test/work/one" to WorkAliasRejection.UnsupportedScheme,
        "//current.test/work/one" to WorkAliasRejection.MalformedUrl,
        "https://user@current.test/work/one" to WorkAliasRejection.UserInfo,
        "https://current.test:8443/work/one" to WorkAliasRejection.NonDefaultPort,
        "https://current.test:/work/one" to WorkAliasRejection.MalformedUrl,
        "https://bad..test/work/one" to WorkAliasRejection.MalformedHost,
        "https://current.test\\evil/work/one" to WorkAliasRejection.MalformedUrl,
        "https://current.test/bad%XX" to WorkAliasRejection.MalformedUrl,
        "https://current.test/bad%" to WorkAliasRejection.MalformedUrl,
        "https://current.test/<bad>" to WorkAliasRejection.MalformedUrl,
        "https://bücher.test/work/one" to WorkAliasRejection.UnsupportedHost,
        "https://[::1]/work/one" to WorkAliasRejection.UnsupportedHost,
        "https://127.0.0.1/work/one" to WorkAliasRejection.UnsupportedHost,
        "https://current.test./work/one" to WorkAliasRejection.UnsupportedHost,
        "https://mirror.test/work/one" to WorkAliasRejection.UndeclaredPageHost,
        "https://images.test/work/one" to WorkAliasRejection.UndeclaredPageHost,
        "https://trusted.test/work/one" to WorkAliasRejection.UndeclaredPageHost,
    )

    private fun rule(
        base: String = "https://current.test",
        previous: Collection<String> = listOf("previous.test", "older.test"),
        incompatible: Boolean = false,
    ) = AcceptedSourceAliasRule("source", base, previous.map { host ->
        SelectedPreviousHost(host, if (incompatible) PreviousHostAuthority.PROVEN_INCOMPATIBLE else PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
            SourceSelectionProofRef(SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, 6, "d".repeat(64)), "source", null))
    })

    private fun policy(rules: Collection<AcceptedSourceAliasRule>) = WorkAliasPolicy(SourceAliasSnapshot(token, rules))

    private fun work(url: String) = WorkLocator("source", url)
}
