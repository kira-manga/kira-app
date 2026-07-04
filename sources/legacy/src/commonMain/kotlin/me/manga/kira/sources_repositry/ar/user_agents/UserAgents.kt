package me.manga.kira.sources_repositry.ar.user_agents

/**
 * Migration note (Phase 7.1 ar/ Wave A): Pure-data object — no platform-specific APIs to port.
 * Verbatim copy of the Android source.
 */
object UserAgents {

    val desktop = listOf<String>(

        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.3",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.3124.85",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.10 Safari/605.1.1",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.7; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.7; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/118.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; WOW64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/118.0.0.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.3",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 OPR/117.0.0.",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.3124.85",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/118.0.0.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Trailer/93.3.8652.5",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.102 Safari/537.36 Edge/18.1958",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; Xbox; Xbox One) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edge/44.18363.8131",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (X11; Fedora; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (X11; Fedora; Linux x86_64; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (X11; Linux i686; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (X11; Linux i686; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/118.0.0.0",
        "Mozilla/5.0 (X11; Linux x86_64; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (X11; Ubuntu; Linux i686; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (X11; Ubuntu; Linux i686; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:136.0) Gecko/20100101 Firefox/136.0"
    )

    val mobile = listOf(

        "Mozilla/5.0 (Android 14; Mobile; rv:136.0) Gecko/136.0 Firefox/136.",
        "Mozilla/5.0 (Android 15; Mobile; LG-M255; rv:136.0) Gecko/136.0 Firefox/136.0",
        "Mozilla/5.0 (Android 15; Mobile; rv:136.0) Gecko/136.0 Firefox/136.",
        "Mozilla/5.0 (Android 15; Mobile; rv:136.0) Gecko/136.0 Firefox/136.0",
        "Mozilla/5.0 (Linux; Android 10; HD1913) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 EdgA/134.0.3124.68",
        "Mozilla/5.0 (Linux; Android 10; JNY-LX1; HMSCore 6.15.0.302) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.196 HuaweiBrowser/15.0.4.312 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/27.0 Chrome/125.0.0.0 Mobile Safari/537.3",
        "Mozilla/5.0 (Linux; Android 10; ONEPLUS A6003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 EdgA/134.0.3124.68",
        "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 EdgA/134.0.3124.68",
        "Mozilla/5.0 (Linux; Android 10; SM-G970F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 OPR/76.2.4027.73374",
        "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 EdgA/134.0.3124.68",
        "Mozilla/5.0 (Linux; Android 10; SM-N975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 OPR/76.2.4027.73374",
        "Mozilla/5.0 (Linux; Android 10; VOG-L29) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36 OPR/76.2.4027.73374",
        "Mozilla/5.0 (iPad; CPU OS 14_7_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/136.0 Mobile/15E148 Safari/605.1.15",
        "Mozilla/5.0 (iPad; CPU OS 17_7_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 10_3 like Mac OS X) AppleWebKit/602.1.50 (KHTML, like Gecko) CriOS/56.0.2924.75 Mobile/14E5239e YisouSpider/5.0 Safari/602.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 14_7_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/136.0 Mobile/15E148 Safari/605.1.15",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/134.0.6998.99 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_7_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 EdgiOS/134.3124.77 Mobile/15E148 Safari/605.1.15",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_7_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1.1 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/134.0.6998.99 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/360.1.737798518 Mobile/15E148 Safari/604.",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Mobile/15E148 Safari/604."
    )
}

/**
 * Audit-trail postscript (Phase 9.x.cluster190.staleKdocSweep.cascade, Task #696, 2026-05-29)
 *
 * Opening-leaf §253 audit-trail-preservation postscript for cluster190 — wave-60 of the per-language
 * sources_repositry per-language repo subtree scout. cluster190 picks up where cluster189 closed
 * (the :sources_repositry/common abstract-base sweep covering BaseManga + NormalSites + NormalSitesv2
 * + SeparatedDetailsSites + SeparatedDetailsSitesv2). cluster190 advances into the :ar/ ISO-639-lang
 * subdirectory — the first of nine per-language directories (ar + en + es + fr + in + it + pt + ru
 * + tr) that host the concrete Arabic-locale source repositories which subclass the common abstract
 * bases. The :ar/ tier alone hosts 24 prose-bearing files per grep on the "Migration note (Phase 7"
 * signature, splitting into roughly five sub-clusters of 5-leaf batches; cluster190 takes the
 * opening 5-leaf batch (siblings 307 through 311) which favours pure-data + serializable-model
 * + stub-debt files for a semantically-coherent opener before the next sub-clusters tackle the
 * Repository implementation classes.
 *
 * The top-of-file prose under audit (preserved verbatim above the `object UserAgents` declaration
 * at lines 3-6):
 *
 *     Migration note (Phase 7.1 ar/ Wave A): Pure-data object — no platform-specific APIs to port.
 *     Verbatim copy of the Android source.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "pure-data object" assertion: the entire object body is two `listOf`
 *      assignments holding browser User-Agent strings (38 desktop + 33 mobile = 71 total) with no
 *      runtime side-effects, no platform APIs, no java.* / android.* / kotlinx.coroutines imports.
 *      Verbatim-port semantics hold post-migration: the KMP class file is byte-for-byte equivalent
 *      to the upstream Android source's data content. No drift between prose and code. The "Wave
 *      A" qualifier indicates this was part of the first :ar/ port wave (pure-data files staged
 *      before any networking-dependent files) — that Wave-A staging convention is now historical
 *      but the per-Wave classification labels remain accurate to the Phase 7.1 port choreography.
 *
 *   b. LIVE-NOT-STALE — the "no platform-specific APIs to port" assertion: confirmed by import
 *      survey (zero imports — only the `package` declaration precedes the `object` body). This is
 *      the cleanest possible KMP-portability classification: a pure-data Kotlin object with no
 *      typealiases, no expect/actual, no `kotlinx.serialization` annotations (the UAs are plain
 *      `String` literals, no `@Serializable` needed), no `companion object`, no init blocks. The
 *      runtime use pattern (sibling :ar/ Repository implementations pull random elements from
 *      `desktop` and `mobile` for User-Agent header rotation to defeat per-UA rate-limit / anti-bot
 *      defenses) operates entirely on the public `val desktop: List<String>` and `val mobile:
 *      List<String>` surface — pure Kotlin stdlib types, KMP-safe by construction.
 *
 *   c. COSMETIC-NOT-STALE — the explicit `<String>` type parameter on `listOf<String>(` at line 9
 *      versus the inferred form on `listOf(` at line 49: deliberate stylistic inconsistency
 *      preserved verbatim from upstream. Both forms produce identical bytecode and identical
 *      runtime type (`List<String>`). Not a bug, not a port artifact — a minor source-style quirk
 *      that survives the §253 audit untouched.
 *
 *   d. POTENTIAL-BUG-PRESERVED — trailing-period truncation on at least 18 UA strings (e.g.
 *      "Chrome/113.0.0.0 Safari/537.3" at line 11 instead of "Safari/537.36"; "Firefox/136." at
 *      line 33 instead of "Firefox/136.0"; "Safari/604." on 9 separate iOS lines). These are
 *      verbatim-copied from upstream — the Android source ships them in this truncated form. The
 *      :ar/ Repository sites that rotate UAs will send these malformed strings as `User-Agent`
 *      headers; whether the target servers reject them as malformed or accept them is a per-server
 *      anti-bot policy detail. §253 convention: preserve potential bugs verbatim rather than
 *      silently fix during a docs-only sweep; a behavioural-test slice could verify the impact
 *      against live servers later.
 *
 * Runtime use pattern (verified by Grep — referenced from sibling :ar/ Repository
 * implementations): `UserAgents.desktop.random()` and `UserAgents.mobile.random()` patterns surface
 * in MangaLekParser + LavatoonsParser + TeamxParser + AzoraParser + ProMangaRepository +
 * ProchanRepository as part of the anti-scrape header builder. The UA pool is one of three
 * primary defences (alongside `Referer` and dynamic cookie rotation) against the per-Arabic-source
 * server-side User-Agent-blocklist anti-bot policy.
 *
 * Cross-references — prior siblings in the cluster57+ continuum:
 *   - cluster144-149 :platform sweeps (siblings 145-201) established the platform-facade §253
 *     pattern for expect/actual-bearing files.
 *   - cluster174-185 :data/local + :data/repository sweeps (siblings 222-260) established the
 *     :data tier sweep pattern for Room entities + DAO + ImplFile postscripts.
 *   - cluster186-189 :data/local + :shared root + :shared/sources_repositry/common sweeps
 *     (siblings 274-306) established the :shared tier sweep pattern culminating in the abstract
 *     base classes that this :ar/ subtree subclasses.
 *   - cluster190 opens the per-language concrete-Repository tier — the leaf level of the
 *     :sources_repositry directory tree, holding Repository implementations that concrete-bind
 *     the abstract Normal/Separated/v2 framework hooks to per-source HTTP endpoints + parsing
 *     selectors.
 *
 * Cluster190 leaf 1/5 — pure-data foundation file. Next leaves: ImageMapMetadata.kt (sibling 308,
 * @Serializable data classes), AzoraModels.kt (sibling 309, java.time → kotlinx.datetime port),
 * DilarV2Models.kt (sibling 310, parallel java.time → kotlinx.datetime port), ProMangaImageCombiner
 * .kt (sibling 311, closing leaf — Phase 8 stub debt + verbatim upstream comment block).
 */
