package me.manga.kira.sources.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Architectural guard for the MangaSource decoupling (2026-07,
 * docs/sources/MANGASOURCE_DECOUPLING_PLAN.md §10): PRODUCTION code outside `:sources:legacy` must
 * never reference the legacy `MangaSource` enum (`sources_repositry.data.MangaSource`,
 * `MangaSource.entries`, `MangaSource.valueOf`) or resurrect a compiled generic-api allow-list
 * (`CONFIG_BACKED_APIS`). Generic sources are defined by validated config stanzas alone; the enum
 * remains legal only inside the legacy module itself and on the explicit allow-list below.
 *
 * Scans the real source tree (JVM test — runs in `:composeApp:desktopTest`, which CI executes), so
 * a reintroduction fails the build rather than surviving as a documentation rule. Comment/KDoc
 * lines are skipped: prose may name the enum; code may not.
 */
class GenericSourcesDecouplingGuardTest {
    /** Production trees under decoupling protection. `:sources:legacy` is deliberately absent. */
    private val protectedModules =
        listOf(
            "composeApp",
            "data",
            "data/local",
            "data/remote",
            "data/download",
            "domain",
            "presentation",
            "ui",
            "platform",
            "core",
            "sources/contracts",
            "sources/engine",
            "sources/config",
            "app",
        )

    /** Documented exceptions (repo-relative): keep this list SHRINKING, never growing silently. */
    private val allowList =
        setOf(
            // Prochan streaming-download fork — Prochan is a permanently-legacy source (canvas
            // de-scramble); the branch is unreachable for generic apis (repo == null on that path).
            "data/download/src/androidMain/kotlin/me/manga/kira/presentation/features/download/domain/ChapterDownloadService.kt",
        )

    private val forbiddenNeedles =
        listOf(
            "sources_repositry.data.MangaSource",
            "MangaSource.entries",
            "MangaSource.valueOf",
            "CONFIG_BACKED_APIS",
        )

    @Test
    fun production_code_outside_the_legacy_module_never_references_the_enum_or_an_api_allowlist() {
        val root = repoRoot()
        val violations = mutableListOf<String>()

        for (module in protectedModules) {
            val srcDir = root.resolve(module).resolve("src")
            if (!srcDir.isDirectory) fail("expected source dir missing: $srcDir — module layout changed?")
            srcDir
                .listFiles { f -> f.isDirectory && (f.name.endsWith("Main") || f.name == "main") }
                .orEmpty()
                .forEach { sourceSet ->
                    sourceSet
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .forEach { file ->
                            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            if (relative in allowList) return@forEach
                            scanFile(file, relative, violations)
                        }
                }
        }

        assertEquals(
            emptyList(),
            violations,
            "generic-source production code re-coupled to the MangaSource enum (or a compiled api " +
                "allow-list). Adding a config-backed source must require ONLY a JSON stanza — see " +
                "docs/sources/MANGASOURCE_DECOUPLING_PLAN.md",
        )
    }

    @Test
    fun the_allow_list_still_points_at_real_files() {
        val root = repoRoot()
        val gone = allowList.filterNot { root.resolve(it).isFile }
        assertEquals(emptyList(), gone, "stale decoupling-guard allow-list entries — prune them")
    }

    /**
     * Config-driven filters guard (2026-07, CONFIG_DRIVEN_FILTERS_PLAN.md §8 item 18): the generic
     * source pipeline — contracts, engine, config — must stay api-AGNOSTIC. A `when (api)` (or a
     * `when (config.api)` / `when (source.api)` / `when (request.api)` etc. — any `when` subject
     * ending in `.api)` or being exactly `api)`) inside these modules means per-source behavior
     * crept back into compiled code instead of the declarative schema. Wider modules legitimately
     * branch on api for legacy routing, so the scan is scoped to the generic pipeline only.
     */
    @Test
    fun the_generic_pipeline_never_branches_on_a_source_api() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        val whenOnApi = Regex("""when\s*\((\w+\.)*api\)""")

        for (module in listOf("sources/contracts", "sources/engine", "sources/config")) {
            val srcDir = root.resolve(module).resolve("src")
            if (!srcDir.isDirectory) fail("expected source dir missing: $srcDir — module layout changed?")
            srcDir
                .listFiles { f -> f.isDirectory && f.name.endsWith("Main") }
                .orEmpty()
                .forEach { sourceSet ->
                    sourceSet
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .forEach { file ->
                            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            file.readLines().forEachIndexed { index, raw ->
                                val line = raw.trim()
                                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEachIndexed
                                if (whenOnApi.containsMatchIn(line.substringBefore("//"))) {
                                    violations += "$relative:${index + 1}: when(api) branching"
                                }
                            }
                        }
                }
        }

        assertEquals(
            emptyList(),
            violations,
            "the generic pipeline branched on a source api — filter/request behavior must come " +
                "from the validated config schema, never per-source Kotlin. See " +
                "docs/sources/CONFIG_DRIVEN_FILTERS_PLAN.md",
        )
    }

    private fun scanFile(
        file: File,
        relative: String,
        violations: MutableList<String>,
    ) {
        file.readLines().forEachIndexed { index, raw ->
            val line = raw.trim()
            // Prose may reference the enum; code may not.
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEachIndexed
            val code = line.substringBefore("//")
            forbiddenNeedles.forEach { needle ->
                if (code.contains(needle)) violations += "$relative:${index + 1}: $needle"
            }
        }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (dir.resolve("settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        fail("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }
}
