package me.manga.kira.sources.runtime

/**
 * The bundled, in-binary source-config document for the Stage-1 config-backed. It ships inside the signed app
 * binary (so it needs no detached signature — it is trusted by virtue of the binary's own signing),
 * is pure DATA (a JSON descriptor the engine interprets — no source-specific Kotlin), and ships the
 * generic-engine config-backed config: every stanza declaring `engine:"generic"` (the validated
 * document is the SINGLE authority for the generic set — see the per-source derivation/divergence
 * notes at the bottom of this file). Every other source stays on the legacy adapter.
 *
 * Reading this from a `composeResources` asset or a signed remote feed is deferred (Stage-2 / remote);
 * a string constant is the minimal correct "bundled config" for the config-backed and keeps the store sync.
 *
 * ## Legacy metadata-only stanzas (SourceRegistry retirement Phase 4 — Option A, 2026-07-04)
 * Every legacy (non-generic) api in the [me.manga.kira.sources_repositry.data.MangaSource]
 * registry now has an `engine:"legacy"` stanza carrying ONLY lifecycle metadata (baseUrl,
 * imageBase, siteState, previousHosts) — no endpoints/fields, nothing executable. They make the
 * config document the single authority for legacy sources' host moves / status / removal too
 * (`SourceCatalogSyncRepositoryImpl` manages their rows; they are never seeded, never declare
 * `engine:"generic"`, and stay force-disabled). Values captured VERBATIM from the production
 * registry feed (`/source/35`, 2026-07-04) so existing endpoint-written rows match byte-for-byte
 * (zero first-launch churn); the 4 apis absent from that feed (Comick, مانجا بارك, Mangapark-It,
 * Batcave) carry their MangaSource code constants. previousHosts declares the 3 live host moves
 * (Dilar: dilar.tube→golden.rest, Komik Cast: komikcast.pics→v1.komikcast05.com, Flowermanga:
 * flowermanga.net→flowermangas.net) so pre-move stored URLs migrate exactly as the endpoint would
 * have. The 12 GENERIC stanzas were deliberately left untouched: their baseUrl is the generic
 * ENGINE's base (e.g. Azora's api.azorafly.com REST API), which legitimately differs from the
 * legacy scraper host the feed advertises — cross-SYSTEM hosts must never enter previousHosts
 * (a host swap across systems would corrupt stored paths). Completeness is pinned by
 * `LegacyStanzaCompletenessTest`.
 *
 * ## Azora descriptor — a worked example, derived field-by-field from the legacy parser
 * (`sources_repositry/ar/azora/AzoraModels.kt`, the parity spec):
 *  - api `"Azora"`, language `"(AR)"` (== `MangaSource.AZORA.API` / `.LANGUAGE.Language`).
 *  - home/popular/search → `GET /api/query?...` → JSON list at `posts`.
 *  - details → `GET {itemUrl}` (the stored `…/api/post/?postId=<id>`) → scalars under `post`, chapters at `post.chapters`.
 *  - pages → `GET {chapterUrl}` (`…/api/chapter?chapterId=<id>`) → image list at `chapter.images`.
 *  - item/chapter URLs are templated from the numeric `id` (mirrors `buildMangaUrl`/`buildChapterUrl`).
 *  - rating defaults to `"0"`, status to `"Unknown"`, description via `clean-html` (== `cleanHtmlContent`),
 *    chapter number via `format-number` + `"Chapter "` (== `formatChapterNumber`), chapter name = title
 *    else `"Chapter <n|format-number>"`, detail rating via `decimal` (Double-string, == `averageRating.toString()`),
 *    dates ISO (== `parseIsoDate`).
 *
 * ## Known, benign divergences from the legacy parser (edge-only; not parity-critical)
 *  - **popular** carries `rating`+`genres` from the same payload that the legacy `PopularManga` dropped.
 *  - **detail title/cover**, when the detail response omits them, fall back to the originating list
 *    item's title/cover (a generic-engine default) rather than legacy's `""`. Azora always populates both.
 *  - **`default` transform** uses `ifBlank` (also fills an empty-string value), slightly broader than the
 *    legacy elvis-on-null. Azora's status/rating are never the empty string in practice.
 *  - **chapter date** uses the timestamp's date part (TZ-independent); legacy used the device-local date.
 *  - **page image order** follows the response array order (Azora returns them pre-ordered by `order`).
 */
const val CONFIG_BACKED_SOURCES_JSON: String = """
{
  "schemaVersion": 1,
  "revision": 3,
  "sources": [
    {
      "api": "Azora",
      "icon": { "resourceKey": "azora" },
      "language": "(AR)",
      "displayName": "Azora",
      "baseUrl": "https://api.azorafly.com",
      "imageBase": "https://api.azorafly.com",
      "engine": "generic",
      "usesCapturedHeaders": false,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/api/query?page={page}&perPage=24&orderBy=lastChapterAddedAt&orderDirection=desc", "format": "json", "root": "posts" },
        "featured": { "url": "{baseUrl}/api/query?page={page}&perPage=24&orderBy=totalViews&orderDirection=desc", "format": "json", "root": "posts" },
        "search":   { "url": "{baseUrl}/api/query?searchTerm={queryEncoded}&perPage=24", "format": "json", "root": "posts" },
        "details": { "url": "{itemUrl}", "format": "json" },
        "pages":   { "url": "{chapterUrl}", "format": "json", "root": "chapter.images" }
      },
      "fields": {
        "item.title":  { "path": "postTitle" },
        "item.url":    { "template": "{baseUrl}/api/post/?postId={id}", "vars": { "id": "id" } },
        "item.cover":  { "path": "featuredImage" },
        "item.rating": { "path": "averageRating", "transform": [ { "fn": "default", "args": { "value": "0" } } ] },
        "item.genres": { "listPath": "genres[*].name" },
        "item.recentChapters": { "listPath": "chapters" },

        "detail.title":       { "path": "post.postTitle" },
        "detail.cover":       { "path": "post.featuredImage" },
        "detail.rating":      { "path": "post.averageRating", "transform": [ { "fn": "decimal" }, { "fn": "default", "args": { "value": "0" } } ] },
        "detail.description": { "path": "post.postContent", "transform": [ { "fn": "clean-html" } ] },
        "detail.author":      { "path": "post.author" },
        "detail.status":      { "path": "post.seriesStatus", "transform": [ { "fn": "default", "args": { "value": "Unknown" } } ] },
        "detail.genres":      { "listPath": "post.genres[*].name" },
        "detail.chapters":    { "listPath": "post.chapters" },

        "chapter.number": { "path": "number", "transform": [ { "fn": "format-number" }, { "fn": "prepend", "args": { "value": "Chapter " } } ] },
        "chapter.name":   { "path": "title", "template": "Chapter {num}", "vars": { "num": "number|format-number" } },
        "chapter.url":    { "template": "{baseUrl}/api/chapter?chapterId={id}", "vars": { "id": "id" } },
        "chapter.date":   { "path": "createdAt", "dateStrategy": "iso" },
        "chapter.locked": { "path": "isLocked" },

        "page.image":     { "path": "url" },
        "page.order":     { "path": "order" }
      }
    },
    {
      "api": "Mangamello",
      "icon": { "resourceKey": "mangamello" },
      "language": "(AR)",
      "displayName": "Mangamello",
      "baseUrl": "https://plus.mangamello.com",
      "imageBase": "https://plus.mangamello.com",
      "engine": "generic",
      "usesCapturedHeaders": false,
      "headers": {
        "accept": "application/json",
        "authorization": "Bearer null",
        "content-type": "application/json",
        "installer": "com.google.android.packageinstaller",
        "user-agent": "Dart/3.3 (dart:io)",
        "vsesion": "1.1.7"
      },
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/api/v1/mangas?sort_by=updated_at&page={page}", "format": "json", "root": "data" },
        "featured": { "url": "{baseUrl}/api/v1/mangas?sort_by=views&page=1", "format": "json", "root": "data" },
        "search":   { "url": "{baseUrl}/api/v1/mangas/search?per_page=40&title={queryEncoded}", "format": "json", "root": "data" },
        "details":  { "url": "{itemUrl}", "format": "json" },
        "chapters": { "url": "{itemUrl}/chapters?per_page=2000", "format": "json", "root": "data" },
        "pages":    { "url": "{chapterUrl}", "format": "json", "root": "data.chapterImages" }
      },
      "fields": {
        "item.title":  { "path": "title" },
        "item.url":    { "template": "{baseUrl}/api/v1/mangas/{id}", "vars": { "id": "id" } },
        "item.cover":  { "path": "img" },
        "item.rating": { "path": "rate", "fallbackPath": "average_rate" },
        "item.genres": { "listPath": "genres[*].name" },

        "detail.title":       { "path": "data.title" },
        "detail.cover":       { "path": "data.img" },
        "detail.rating":      { "path": "data.ten_rate", "transform": [ { "fn": "decimal" } ] },
        "detail.description": { "path": "data.summary" },
        "detail.status":      { "path": "data.status", "transform": [ { "fn": "enum-map", "args": { "3": "مكتمل", "__default__": "مستمر" } } ] },

        "chapter.number": { "path": "order", "fallbackPath": "title", "transform": [ { "fn": "decimal" } ] },
        "chapter.name":   { "path": "title" },
        "chapter.url":    { "template": "{baseUrl}/api/v1/mangas/{mangaId}/chapters/{id}?relations=chapterImages", "vars": { "mangaId": "manga_id", "id": "id" } },
        "chapter.date":   { "path": "created_at", "dateStrategy": "iso" },

        "page.image":     { "path": "src", "fallbackPath": "originalSrc" }
      }
    },
    {
      "api": "Mangamello Plus",
      "icon": { "resourceKey": "mangamello_plus" },
      "language": "(AR)",
      "displayName": "Mangamello Plus",
      "baseUrl": "https://plus.mangamello.com",
      "imageBase": "https://plus.mangamello.com",
      "engine": "generic",
      "usesCapturedHeaders": false,
      "headers": {
        "accept": "application/json",
        "authorization": "Bearer null",
        "content-type": "application/json",
        "installer": "com.google.android.packageinstaller",
        "user-agent": "Dart/3.3 (dart:io)",
        "vsesion": "1.1.7"
      },
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/api/v1/mangas?sort_by=updated_at&page={page}", "format": "json", "root": "data" },
        "featured": { "url": "{baseUrl}/api/v1/mangas?sort_by=views&page=1", "format": "json", "root": "data" },
        "search":   { "url": "{baseUrl}/api/v1/mangas/search?per_page=40&title={queryEncoded}", "format": "json", "root": "data" },
        "details":  { "url": "{itemUrl}", "format": "json" },
        "chapters": { "url": "{itemUrl}/chapters?per_page=2000", "format": "json", "root": "data" },
        "pages":    { "url": "{chapterUrl}", "format": "json", "root": "data.chapterImages" }
      },
      "fields": {
        "item.title":  { "path": "title" },
        "item.url":    { "template": "{baseUrl}/api/v1/mangas/{id}", "vars": { "id": "id" } },
        "item.cover":  { "path": "img" },
        "item.rating": { "path": "rate", "fallbackPath": "average_rate" },
        "item.genres": { "listPath": "genres[*].name" },

        "detail.title":       { "path": "data.title" },
        "detail.cover":       { "path": "data.img" },
        "detail.rating":      { "path": "data.ten_rate", "transform": [ { "fn": "decimal" } ] },
        "detail.description": { "path": "data.summary" },
        "detail.status":      { "path": "data.status", "transform": [ { "fn": "enum-map", "args": { "3": "مكتمل", "__default__": "مستمر" } } ] },

        "chapter.number": { "path": "order", "fallbackPath": "title", "transform": [ { "fn": "decimal" } ] },
        "chapter.name":   { "path": "title" },
        "chapter.url":    { "template": "{baseUrl}/api/v1/mangas/{mangaId}/chapters/{id}?relations=chapterImages", "vars": { "mangaId": "manga_id", "id": "id" } },
        "chapter.date":   { "path": "created_at", "dateStrategy": "iso" },

        "page.image":     { "path": "src", "fallbackPath": "originalSrc" }
      }
    },
    {
      "api": "SwatManga",
      "icon": { "resourceKey": "swatmanga" },
      "language": "(AR)",
      "displayName": "SwatManga",
      "baseUrl": "https://appswat.com/v2/api/v1",
      "imageBase": "https://appswat.com",
      "engine": "generic",
      "usesCapturedHeaders": false,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/series/releases/?page={page}&page_size=20", "format": "json", "root": "results" },
        "featured": { "url": "{baseUrl}/chapters/?limit=20&offset=1&created_last=week&order_by=-views_count", "format": "json", "root": "results" },
        "search":   { "url": "{baseUrl}/series/?search={queryEncoded}&page=1&page_size=20", "format": "json", "root": "results" },
        "details":  { "url": "{baseUrl}/series/{id}/", "format": "json" },
        "chapters": { "url": "{baseUrl}/series/{id}/chapters/?page={page}&page_size=200", "format": "json", "root": "results", "pageParam": "page", "lastPageLocator": "next" },
        "pages":    { "url": "{chapterUrl}", "format": "json", "root": "images" }
      },
      "fields": {
        "item.title":  { "path": "serie.title", "fallbackPath": "title" },
        "item.url":    { "template": "{baseUrl}/{id}", "vars": { "id": "serie_id, serie.id, id" } },
        "item.cover":  { "path": "serie.poster.medium", "fallbackPath": "poster.medium" },
        "item.rating": { "path": "rating", "transform": [ { "fn": "default", "args": { "value": "0" } } ] },
        "item.genres": { "listPath": "genres[*].name" },

        "detail.title":       { "path": "title" },
        "detail.cover":       { "path": "poster.medium" },
        "detail.rating":      { "path": "rating" },
        "detail.description": { "path": "story" },
        "detail.status":      { "path": "status.name", "transform": [ { "fn": "enum-map", "args": { "ongoing": "Ongoing", "completed": "Completed", "hiatus": "Hiatus", "cancelled": "Cancelled", "__default__": "Unknown" } } ] },
        "detail.genres":      { "listPath": "genres[*].name" },

        "chapter.number": { "path": "chapter" },
        "chapter.name":   { "path": "title" },
        "chapter.url":    { "template": "{baseUrl}/chapters/{id}/", "vars": { "id": "id|format-number" } },
        "chapter.date":   { "path": "created_at", "dateStrategy": "iso" },

        "page.image":     { "path": "image" }
      }
    },
    {
      "api": "Lekmanga",
      "icon": { "resourceKey": "lekmanga" },
      "language": "(AR)",
      "displayName": "Lekmanga",
      "baseUrl": "https://lek-manga.net",
      "imageBase": "https://io.lek-manga.net",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/page/{page}/", "format": "html", "listSelector": ".page-item-detail.manga" },
        "featured": { "url": "{baseUrl}/manga/page/{page}/?m_orderby=views", "format": "html", "listSelector": ".page-item-detail.manga" },
        "search":   { "url": "{baseUrl}/wp-admin/admin-ajax.php", "method": "post-form", "format": "html", "listSelector": ".page-item-detail.manga",
          "formBody": { "action": "madara_load_more", "page": "0", "template": "madara-core/content/content-archive", "vars[s]": "{query}", "vars[posts_per_page]": "25", "vars[orderby]": "meta_value_num", "vars[paged]": "1", "vars[sidebar]": "right" } },
        "details":  { "url": "{itemUrl}", "format": "html" },
        "pages":    { "url": "{chapterUrl}", "format": "html", "listSelector": "div.reading-content img.wp-manga-chapter-img" }
      },
      "fields": {
        "item.title":  { "selector": ".post-title a", "attr": "text" },
        "item.url":    { "selector": ".post-title a", "attr": "abs:href" },
        "item.cover":  { "selector": ".item-thumb img", "attr": "abs:src" },
        "item.rating": { "selector": ".post-total-rating .score", "attr": "text" },
        "item.recentChapters": { "listSelector": ".list-chapter .chapter-item" },

        "detail.title":       { "selector": "div.post-title h1", "attr": "text" },
        "detail.cover":       { "selector": "div.summary_image img", "attr": "src" },
        "detail.rating":      { "selector": "span#averagerate", "attr": "text", "transform": [ { "fn": "default", "args": { "value": "0" } } ] },
        "detail.description": { "selector": "div.summary__content", "attr": "text", "transform": [ { "fn": "trim" } ] },
        "detail.author":      { "selector": "div.author-content", "attr": "text" },
        "detail.status":      { "selector": "div.summary-heading:contains(الحالة) + div.summary-content", "attr": "text" },
        "detail.genres":      { "listSelector": "div.genres-content a" },
        "detail.chapters":    { "listSelector": "ul.main.version-chap li.wp-manga-chapter" },

        "chapter.number": { "selector": "a", "attr": "text", "transform": [ { "fn": "trim" }, { "fn": "prepend", "args": { "value": "Chapter " } } ] },
        "chapter.name":   { "selector": "a", "attr": "text", "transform": [ { "fn": "trim" } ] },
        "chapter.url":    { "selector": "a", "attr": "abs:href" },

        "page.image":     { "selector": "", "attr": "src" }
      }
    },
    {
      "api": "Team X",
      "icon": { "resourceKey": "team_x" },
      "language": "(AR)",
      "displayName": "Team X",
      "baseUrl": "https://olympustaff.com",
      "imageBase": "https://olympustaff.com",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/?page={page}", "format": "html", "listSelector": "div.post-body .box" },
        "featured": { "url": "{baseUrl}/", "format": "html", "listSelector": "div.swiper-slide:has(.entry-title a)" },
        "search":   { "url": "{baseUrl}/ajax/search?keyword={queryEncoded}", "format": "html", "listSelector": "a.items-center" },
        "details":  { "url": "{itemUrl}", "format": "html" },
        "chapters": { "url": "{itemUrl}?page={page}", "format": "html", "listSelector": "div.chapter-card", "pageParam": "page", "lastPageLocator": "ul.pagination li.page-item a.page-link" },
        "pages":    { "url": "{chapterUrl}", "format": "html", "listSelector": "div.image_list img" }
      },
      "fields": {
        "item.title": { "selector": "div.info a h3", "attr": "text", "fallbackSelectors": [ "h4" ] },
        "item.url":   { "selector": "", "attr": "abs:href", "fallbackSelectors": [ "div.info a" ] },
        "item.cover": { "selector": "div.imgu a img", "attr": "src", "fallbackSelectors": [ "img" ] },

        "featured.item.title": { "selector": ".entry-title a", "attr": "text" },
        "featured.item.url":   { "selector": ".entry-title a", "attr": "abs:href" },
        "featured.item.cover": { "selector": ".entry-image img", "attr": "abs:src" },

        "detail.title":       { "selector": "div.author-info-title h1", "attr": "text" },
        "detail.cover":       { "selector": "div.text-right img.shadow-sm", "attr": "abs:src" },
        "detail.rating":      { "selector": "div#average_rating", "attr": "text" },
        "detail.description": { "selector": "div.review-content p", "attr": "text" },
        "detail.genres":      { "listSelector": "div.review-author-info a.subtitle" },

        "chapter.number": { "selector": "", "attr": "data-number" },
        "chapter.name":   { "selector": "div.chapter-title", "attr": "text" },
        "chapter.url":    { "selector": "a.chapter-link", "attr": "href" },
        "chapter.date":   { "selector": "", "attr": "data-date", "dateStrategy": "epoch-seconds" },

        "page.image": { "selector": "", "attr": "abs:src", "lazyAttrChain": [ "abs:data-src" ] }
      }
    },
    {
      "api": "DilarV2",
      "icon": { "resourceKey": "dilar" },
      "language": "(AR)",
      "displayName": "DilarV2",
      "baseUrl": "https://dilar.tube",
      "imageBase": "https://dilar.tube/uploads",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "headers": { "Referer": "https://dilar.tube" },
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/api/series/?page={page}", "format": "json", "root": "series" },
        "featured": { "url": "{baseUrl}/api/series/popular?page={page}", "format": "json", "root": "series" },
        "search":   { "url": "{baseUrl}/api/search/quick_search", "method": "post-json", "format": "json", "root": "[*].data[*]",
          "jsonBody": "{\"query\":\"{queryJson}\",\"includes\":[\"Manga\"]}",
          "listFilters": [
            { "path": "series_type.name", "op": "equals", "value": "Novel", "mode": "exclude" },
            { "path": "series_type.title", "op": "equals", "value": "رواية", "mode": "exclude" },
            { "path": "deleted_at", "op": "isNull", "mode": "include" }
          ] },
        "details":  { "url": "{itemUrl}", "format": "json" },
        "chapters": { "url": "{baseUrl}/api/series/{id}/chapters", "format": "json", "root": "chapters" },
        "pages":    { "url": "{chapterUrl}", "format": "json", "root": "webp_pages,pages", "rootDirs": [ "hq_webp", "hq" ] }
      },
      "fields": {
        "item.title":  { "path": "title" },
        "item.url":    { "template": "{baseUrl}/api/series/{id}", "vars": { "id": "id" } },
        "item.cover":  { "template": "{imageBase}/manga/cover/{id}/{cover}", "vars": { "id": "id", "cover": "cover" } },
        "item.rating": { "path": "rating" },

        "detail.title":       { "path": "title" },
        "detail.cover":       { "template": "{imageBase}/manga/cover/{id}/{cover}", "vars": { "id": "id", "cover": "cover" } },
        "detail.rating":      { "path": "rating", "transform": [ { "fn": "default", "args": { "value": "0" } } ] },
        "detail.description": { "path": "summary" },
        "detail.author":      { "path": "creator.nick" },
        "detail.status":      { "path": "translation_status", "transform": [ { "fn": "enum-map", "args": { "ongoing": "Ongoing", "completed": "Completed", "hiatus": "Hiatus", "__default__": "Unknown" } } ] },
        "detail.genres":      { "listPath": "categories[*].name" },

        "chapter.number": { "path": "chapter", "transform": [ { "fn": "format-number" }, { "fn": "prepend", "args": { "value": "Chapter " } } ] },
        "chapter.name":   { "path": "title", "template": "Chapter {num}", "vars": { "num": "chapter|format-number" } },
        "chapter.url":    { "template": "{baseUrl}/api/chapters/{releaseId}", "vars": { "releaseId": "releases[0].id" } },
        "chapter.date":   { "path": "created_at", "dateStrategy": "iso" },
        "chapter.locked": { "path": "lock" },

        "page.image": { "template": "{imageBase}/releases/{storageKey}/{dir}/{pageUrl}", "vars": { "storageKey": "root:storage_key", "dir": "root:__dir", "pageUrl": "url" } },
        "page.order": { "path": "order" }
      }
    },
    {
      "api": "3asq",
      "icon": { "resourceKey": "3asq" },
      "language": "(AR)",
      "displayName": "3asq",
      "baseUrl": "https://3asq.org",
      "imageBase": "https://3asq.org",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/manga/page/{page}/?m_orderby=latest", "format": "html", "listSelector": ".page-item-detail.manga" },
        "featured": { "url": "{baseUrl}/manga/page/{page}/?m_orderby=views", "format": "html", "listSelector": ".page-item-detail.manga" },
        "search":   { "url": "{baseUrl}/wp-admin/admin-ajax.php", "method": "post-form", "format": "html", "listSelector": "div.row.c-tabs-item__content",
          "formBody": { "action": "madara_load_more", "vars[s]": "{query}", "vars[posts_per_page]": "20", "template": "madara-core/content/content-search" } },
        "details":  { "url": "{itemUrl}", "format": "html" },
        "chapters": { "url": "{itemUrl}ajax/chapters", "method": "post-form", "format": "html", "listSelector": "ul.main.version-chap.no-volumn li.wp-manga-chapter", "formBody": { "action": "manga_get_chapters" } },
        "pages":    { "url": "{chapterUrl}", "format": "html", "listSelector": "img.wp-manga-chapter-img" }
      },
      "fields": {
        "item.title": { "selector": ".item-thumb a", "attr": "title", "fallbackSelectors": [ ".tab-thumb a" ] },
        "item.url":   { "selector": ".item-thumb a", "attr": "abs:href", "fallbackSelectors": [ ".tab-thumb a" ] },
        "item.cover": { "selector": ".item-thumb img", "attr": "abs:src", "fallbackSelectors": [ ".tab-thumb img" ] },

        "detail.title":       { "selector": "div.post-title h1", "attr": "text" },
        "detail.cover":       { "selector": "div.summary_image img", "attr": "abs:src" },
        "detail.rating":      { "selector": "span#averagerate", "attr": "text", "transform": [ { "fn": "default", "args": { "value": "0" } } ] },
        "detail.description": { "selector": "meta[name=description]", "attr": "content" },
        "detail.author":      { "selector": "div.summary-heading:contains(الكاتب) + div.summary-content a", "attr": "text" },
        "detail.status":      { "selector": "span#__status_none__", "template": "Unknown" },
        "detail.genres":      { "listSelector": "div.summary-heading:contains(التصنيفات) + div.summary-content a" },

        "chapter.name":   { "selector": "a", "attr": "text" },
        "chapter.number": { "selector": "a", "attr": "text", "transform": [ { "fn": "regex-extract", "args": { "pattern": "\\d+(\\.\\d+)?", "which": "last" } } ] },
        "chapter.url":    { "selector": "a", "attr": "abs:href" },

        "page.image": { "selector": "", "attr": "abs:src" }
      }
    },
    {
      "api": "Demonicscans",
      "icon": { "resourceKey": "demonicscans" },
      "language": "(EN)",
      "displayName": "Demonicscans",
      "baseUrl": "https://demonicscans.org",
      "imageBase": "https://demonicscans.org",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":    { "url": "{baseUrl}/lastupdates.php?list={page}", "format": "html", "listSelector": "#updates-container .updates-element" },
        "search":  { "url": "{baseUrl}/search.php?manga={queryEncoded}", "format": "html", "listSelector": "a:has(img.search-thumb)" },
        "details": { "url": "{itemUrl}", "format": "html" },
        "featured": { "url": "{baseUrl}/", "format": "html", "listSelector": "#carousel .owl-element" },
        "pages":   { "url": "{chapterUrl}", "format": "html", "listSelector": "img.imgholder:not([src*='free_ads.jpg']):not([src*='btn_close.gif'])" }
      },
      "fields": {
        "item.title": { "selector": ".updates-element-info h2 a", "attr": "text", "fallbackSelectors": [ "div.flex.flex-col div" ] },
        "item.url":   { "selector": "", "attr": "abs:href", "fallbackSelectors": [ ".updates-element-info h2 a" ] },
        "item.cover": { "selector": ".thumb img", "attr": "abs:src", "fallbackSelectors": [ "img.search-thumb" ] },
        "item.recentChapters": { "listSelector": ".chap-date" },

        "featured.item.title": { "selector": "a", "attr": "title" },
        "featured.item.url":   { "selector": "a", "attr": "abs:href" },
        "featured.item.cover": { "selector": "img", "attr": "abs:src" },

        "detail.title":       { "selector": "#manga-info-rightColumn h1", "attr": "text" },
        "detail.cover":       { "selector": "#manga-page img", "attr": "abs:src" },
        "detail.rating":      { "selector": "#R-V-B .RVB", "attr": "text" },
        "detail.description": { "selector": "#manga-info-rightColumn .white-font", "attr": "text", "transform": [ { "fn": "clean-html" } ] },
        "detail.author":      { "selector": "#manga-info-stats div.flex.flex-row:has(li:contains(Author)) li:nth-child(2)", "attr": "text" },
        "detail.status":      { "selector": "#manga-info-stats div.flex.flex-row:has(li:contains(Status)) li:nth-child(2)", "attr": "text" },
        "detail.genres":      { "listSelector": ".genres-list li" },
        "detail.chapters":    { "listSelector": "#chapters-list li" },

        "chapter.number": { "selector": "a", "attr": "ownText", "transform": [ { "fn": "substring-after", "args": { "delimiter": "Chapter " } }, { "fn": "trim" } ] },
        "chapter.name":   { "selector": "a", "attr": "ownText", "transform": [ { "fn": "trim" } ] },
        "chapter.url":    { "selector": "a", "attr": "abs:href" },
        "chapter.date":   { "selector": "span[style*='float:right']", "attr": "text", "dateStrategy": "iso" },

        "page.image":     { "selector": "", "attr": "abs:src" }
      }
    },
    {
      "api": "Mangabuddy",
      "icon": { "resourceKey": "mangabuddy" },
      "language": "(EN)",
      "displayName": "Mangabuddy",
      "baseUrl": "https://mangak.io",
      "imageBase": "https://mangak.io",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "headers": { "Referer": "https://mangak.io/" },
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "https://api.mangak.io/titles/home", "format": "json", "root": "data.latest.items" },
        "featured": { "url": "https://api.mangak.io/titles/home", "format": "json", "root": "data.popular" },
        "search":   { "url": "https://api.mangak.io/titles/search?q={queryEncoded}", "format": "json", "root": "data.items" },
        "details":  { "url": "{itemUrl}", "format": "json" },
        "chapters": { "url": "{itemUrl}/chapters", "format": "json", "root": "data.chapters" },
        "pages":    { "url": "{chapterUrl}", "format": "script-json", "root": "props.pageProps.initialChapter.images" }
      },
      "fields": {
        "item.title":  { "path": "name" },
        "item.url":    { "template": "https://api.mangak.io/titles/{id}", "vars": { "id": "id" } },
        "item.cover":  { "path": "cover" },
        "item.genres": { "listPath": "genres[*].name" },

        "detail.title":       { "path": "data.title.name" },
        "detail.cover":       { "path": "data.title.cover" },
        "detail.status":      { "path": "data.title.status" },
        "detail.description": { "path": "data.title.summary" },
        "detail.genres":      { "listPath": "data.title.genres[*].name" },

        "chapter.number": { "path": "chapter_number" },
        "chapter.name":   { "path": "name" },
        "chapter.url":    { "path": "url" },
        "chapter.date":   { "path": "updated_at", "dateStrategy": "iso" },

        "page.image": { "path": "" }
      }
    },
    {
      "api": "Zazamanga",
      "icon": { "resourceKey": "zazamanga" },
      "language": "(EN)",
      "displayName": "Zazamanga",
      "baseUrl": "https://www.zazamanga.com",
      "imageBase": "https://www.zazamanga.com",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "headers": { "Referer": "https://www.zazamanga.com/" },
      "blacklistGenres": [ "hentai", "smut", "yaoi", "yuri", "shoujo-ai", "shounen-ai", "sexual-violence", "shota", "loli", "incest", "erotica", "sm_bdsm", "master_servant", "fetish", "nsfw", "pornographic" ],
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "{baseUrl}/manga?orderby=latest&page={page}", "format": "html", "listSelector": "div.page-item-detail.manga" },
        "featured": { "url": "{baseUrl}/manga?orderby=views", "format": "html", "listSelector": "div.page-item-detail.manga" },
        "search":   { "url": "{baseUrl}/?s={queryEncoded}&post_type=wp-manga", "format": "html", "listSelector": "div.page-item-detail.manga" },
        "details":  { "url": "{itemUrl}", "format": "html" },
        "pages":    { "url": "{chapterUrl}", "format": "html", "listSelector": "img.wp-manga-chapter-img" }
      },
      "fields": {
        "item.title":  { "selector": ".post-title a", "attr": "text" },
        "item.url":    { "selector": ".item-thumb a", "attr": "abs:href" },
        "item.cover":  { "selector": ".item-thumb img", "attr": "abs:src" },
        "item.genres": { "listSelector": "div.tags a", "attr": "text" },
        "item.recentChapters": { "listSelector": ".list-chapter .chapter" },

        "detail.title":       { "selector": "h1.post-title", "attr": "text" },
        "detail.cover":       { "selector": "div.summary_image img", "attr": "abs:data-backup", "lazyAttrChain": [ "abs:src" ] },
        "detail.description": { "selector": "div.description-summary", "attr": "text" },
        "detail.rating":      { "selector": "#averagerate", "attr": "text" },
        "detail.genres":      { "listSelector": ".post-content > .tags a[rel=tag]", "attr": "text" },
        "detail.chapters":    { "listSelector": ".wp-manga-chapter" },

        "chapter.number": { "selector": "a", "attr": "text", "transform": [ { "fn": "trim" } ] },
        "chapter.name":   { "selector": "a", "attr": "text", "transform": [ { "fn": "trim" } ] },
        "chapter.url":    { "selector": "a", "attr": "abs:href" },

        "page.image":     { "selector": "", "attr": "abs:src" }
      }
    },
    {
      "api": "Tapas",
      "icon": { "resourceKey": "tapas" },
      "language": "(EN)",
      "displayName": "Tapas",
      "baseUrl": "https://tapas.io",
      "imageBase": "https://tapas.io",
      "engine": "generic",
      "usesCapturedHeaders": true,
      "headers": { "Referer": "https://m.tapas.io", "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:105.0) Gecko/20100101 Firefox/105.0" },
      "blacklistGenres": [ "BL", "LGBTQ+", "GL" ],
      "pagination": { "type": "page-number", "param": "page", "start": 1 },
      "endpoints": {
        "home":     { "url": "https://story-api.tapas.io/cosmos/api/v1/landing/genre?category_type=COMIC&sort_option=NEWEST_EPISODE&subtab_id=17&size=20&page={pageOffset}", "format": "json", "root": "data.items" },
        "featured": { "url": "https://story-api.tapas.io/cosmos/api/v1/landing/ranking?category_type=COMIC&subtab_id=17&size=20&page=0", "format": "json", "root": "data.items" },
        "search":   { "url": "{baseUrl}/search?pageNumber=1&q={queryEncoded}&t=COMICS", "format": "html", "listSelector": "a.thumb-wrap[data-series-id]" },
        "details":  { "url": "{baseUrl}/series/{id}/info", "format": "html" },
        "chapters": { "url": "{baseUrl}/series/{id}/episodes?page={page}", "format": "json", "root": "data.episodes", "pageParam": "page", "lastPageLocator": "data.pagination.has_next" },
        "pages":    { "url": "{chapterUrl}", "format": "html", "listSelector": "img.content__img" }
      },
      "fields": {
        "item.title":  { "path": "title" },
        "item.url":    { "template": "{baseUrl}/series/{id}", "vars": { "id": "seriesId" } },
        "item.cover":  { "path": "assetProperty.bookCoverImage.path", "transform": [ { "fn": "append", "args": { "value": ".png" } } ] },
        "item.genres": { "listPath": "genreList[*].value" },

        "search.item.title": { "selector": "img", "attr": "alt", "transform": [ { "fn": "remove", "args": { "value": "#_h_i_g_h_L_i_g_h_t_#" } }, { "fn": "remove", "args": { "value": "#/_h_i_g_h_L_i_g_h_t_#" } } ] },
        "search.item.url":   { "selector": "", "attr": "data-series-id", "transform": [ { "fn": "prepend", "args": { "value": "https://tapas.io/series/" } } ] },
        "search.item.cover": { "selector": "img", "attr": "abs:src" },

        "detail.title":       { "selector": ".info__right .title", "attr": "text" },
        "detail.cover":       { "selector": ".thumb.js-thumbnail img", "attr": "abs:src" },
        "detail.description": { "selector": ".description__body", "attr": "text" },
        "detail.genres":      { "listSelector": ".genre-btn" },

        "chapter.number": { "path": "scene" },
        "chapter.name":   { "path": "title" },
        "chapter.url":    { "template": "{baseUrl}/episode/{id}", "vars": { "id": "id" } },
        "chapter.date":   { "path": "publish_date", "dateStrategy": "iso" },

        "page.image": { "selector": "", "attr": "abs:data-src" }
      }
    },
    {
      "api": "Lavatoons",
      "language": "(AR)",
      "baseUrl": "https://lavascans.com",
      "imageBase": "https://lavascans.com",
      "engine": "legacy"
    },
    {
      "api": "Mangatuk",
      "language": "(AR)",
      "baseUrl": "https://mangatuk.com/",
      "imageBase": "https://mangatuk.com/",
      "engine": "legacy"
    },
    {
      "api": "Dilar",
      "language": "(AR)",
      "baseUrl": "https://golden.rest/",
      "imageBase": "https://dilar.tube/",
      "engine": "legacy",
      "previousHosts": ["dilar.tube"]
    },
    {
      "api": "Promanga",
      "language": "(AR)",
      "baseUrl": "https://api.prochan.net/",
      "imageBase": "https://storage.promanga.net/",
      "engine": "legacy",
      "siteState": "ADULT_18_PLUS"
    },
    {
      "api": "Prochan",
      "language": "(AR)",
      "baseUrl": "https://prochan.net/",
      "imageBase": "https://cdn1.prochan.net/",
      "engine": "legacy",
      "siteState": "ADULT_18_PLUS"
    },
    {
      "api": "Batoto",
      "language": "(EN)",
      "baseUrl": "https://bato.to/",
      "engine": "legacy"
    },
    {
      "api": "Manhwatop",
      "language": "(EN)",
      "baseUrl": "https://manhwatop.com/",
      "imageBase": "https://manhwatop.com/",
      "engine": "legacy"
    },
    {
      "api": "Comick",
      "language": "(EN)",
      "baseUrl": "https://comick.io/",
      "engine": "legacy"
    },
    {
      "api": "Mangapark",
      "language": "(EN)",
      "baseUrl": "https://mangapark.io/apo/",
      "imageBase": "https://mangapark.io/",
      "engine": "legacy",
      "siteState": "STOPPED"
    },
    {
      "api": "مانجا بارك",
      "language": "(AR)",
      "baseUrl": "https://mangapark.io/apo/",
      "engine": "legacy"
    },
    {
      "api": "Mangapark-It",
      "language": "(IT)",
      "baseUrl": "https://mangapark.io/apo/",
      "engine": "legacy"
    },
    {
      "api": "Mangapark-Es",
      "language": "(ES)",
      "baseUrl": "https://mangapark.io/apo/",
      "imageBase": "https://mangapark.io/apo",
      "engine": "legacy",
      "siteState": "STOPPED"
    },
    {
      "api": "Mangapark-Es-La",
      "language": "(ES)",
      "baseUrl": "https://mangapark.io/apo/",
      "imageBase": "https://mangapark.io/apo",
      "engine": "legacy",
      "siteState": "STOPPED"
    },
    {
      "api": "Olympusbiblioteca",
      "language": "(ES)",
      "baseUrl": "https://olympusbiblioteca.com/",
      "imageBase": "https://olympusbiblioteca.com",
      "engine": "legacy"
    },
    {
      "api": "Manhwaweb",
      "language": "(ES)",
      "baseUrl": "https://manhwaweb.com/",
      "imageBase": "https://manhwaweb.com/",
      "engine": "legacy"
    },
    {
      "api": "Taurus Fansub",
      "language": "(ES)",
      "baseUrl": "https://taurus.topmanhuas.org/",
      "imageBase": "https://taurus.topmanhuas.org/",
      "engine": "legacy"
    },
    {
      "api": "Inmanga",
      "language": "(ES)",
      "baseUrl": "https://inmanga.com/",
      "imageBase": "https://pack-yak.intomanga.com/",
      "engine": "legacy"
    },
    {
      "api": "Komik Cast",
      "language": "(IN)",
      "baseUrl": "https://v1.komikcast05.com/",
      "imageBase": "https://v1.komikcast05.com/",
      "engine": "legacy",
      "previousHosts": ["komikcast.pics"]
    },
    {
      "api": "Komiku",
      "language": "(IN)",
      "baseUrl": "https://komiku.org/",
      "imageBase": "https://thumbnail.komiku.org/",
      "engine": "legacy"
    },
    {
      "api": "Manga Origine",
      "language": "(FR)",
      "baseUrl": "https://mangas-origines.fr/",
      "imageBase": "https://mangas-origines.fr/",
      "engine": "legacy"
    },
    {
      "api": "Raijinscan",
      "language": "(FR)",
      "baseUrl": "https://raijin-scans.fr/",
      "imageBase": "https://raijin-scans.fr/",
      "engine": "legacy"
    },
    {
      "api": "Manhastro",
      "language": "(PT)",
      "baseUrl": "https://api2.manhastro.net/",
      "imageBase": "https://capa.manhastro.net/",
      "engine": "legacy"
    },
    {
      "api": "Flowermanga",
      "language": "(PT)",
      "baseUrl": "https://flowermangas.net/",
      "imageBase": "https://flowermangas.net/",
      "engine": "legacy",
      "previousHosts": ["flowermanga.net"]
    },
    {
      "api": "Mediocretoons",
      "language": "(PT)",
      "baseUrl": "https://api.mediocretoons.com/",
      "imageBase": "https://cdn.mediocretoons.com/",
      "engine": "legacy"
    },
    {
      "api": "Desu",
      "language": "(RU)",
      "baseUrl": "https://desu.city/",
      "imageBase": "https://static.desu.city/",
      "engine": "legacy"
    },
    {
      "api": "Mangahub",
      "language": "(RU)",
      "baseUrl": "https://mangahub.ru/",
      "imageBase": "https://p1.statichub.org/",
      "engine": "legacy"
    },
    {
      "api": "Batcave",
      "language": "(EN)",
      "baseUrl": "https://batcave.biz/",
      "engine": "legacy"
    },
    {
      "api": "Timenaight",
      "language": "(TR)",
      "baseUrl": "https://timenaight.org/",
      "imageBase": "https://timenaight.org",
      "engine": "legacy"
    },
    {
      "api": "Webtoontr",
      "language": "(TR)",
      "baseUrl": "https://webtoontr.net/",
      "imageBase": "https://webtoontr.net",
      "engine": "legacy"
    },
    {
      "api": "Webtoonhatti",
      "language": "(TR)",
      "baseUrl": "https://webtoonhatti.club/",
      "imageBase": "https://webtoonhatti.club",
      "engine": "legacy"
    },
    {
      "api": "Mangaworld",
      "language": "(IT)",
      "baseUrl": "https://mangaworld.cx/",
      "imageBase": "https://mangaworld.cx",
      "engine": "legacy"
    },
    {
      "api": "Senkuro",
      "language": "(RU)",
      "baseUrl": "https://api.senkuro.com/graphql",
      "imageBase": "https://api.senkuro.com/graphql",
      "engine": "legacy"
    },
    {
      "api": "Sussytoons",
      "language": "(PT)",
      "baseUrl": "https://api2.sussytoons.wtf/",
      "imageBase": "https://api2.sussytoons.wtf/",
      "engine": "legacy",
      "siteState": "STOPPED"
    }
  ]
}
"""

/*
 * ## Per-source conversion notes (the generic set)
 *
 * Which sources are generic is declared IN the JSON above — every stanza with `engine:"generic"`
 * (12 as of 2026-07). There is deliberately no compiled api allow-list any more (MangaSource
 * decoupling, 2026-07): the validated document is the single authority, and
 * `DefaultSourceRegistry.isConfigBacked` derives from it. The owner's rule is **no per-verb legacy
 * split**: each generic source is migrated FULLY generic (every verb it supports goes through the
 * engine) or stays fully legacy — never half-and-half. Since the registry went generic-ONLY,
 * `FallbackSourceClient` is UNWIRED — a generic failure surfaces as a classified error; no migrated
 * source's operation depends on any legacy fallback.
 *
 * All 12 are fully generic: Azora, Mangamello, Mangamello Plus, SwatManga, DilarV2, 3asq, Lekmanga, Team X,
 * Zazamanga, Demonicscans, Mangabuddy, Tapas. Per-source notes:
 *  - **Lekmanga** — Cloudflare-gated, so verified ON-DEVICE (2026-06-07) with a WebView-solved
 *    `cf_clearance` replayed in curl from the same public IP, NOT by plain curl. All verbs confirmed
 *    against the real CF-cleared HTML: home (`/page/{page}/`), featured = the all-time-views ranking
 *    (`/manga/page/{page}/?m_orderby=views`, distinct from latest), search (admin-ajax `madara_load_more`),
 *    details, inline chapters, reader pages. The on-device pass also caught + fixed a real bug — the
 *    `detail.status` selector now matches the live DOM (label in `summary-heading`, value in the sibling
 *    `summary-content`). At runtime the app reuses its captured `cf_clearance`; if it expires →
 *    interstitial → `Failure` → fallback until the WebView re-solves.
 *  - **Mangabuddy** — the site rebuilt to mangak.io (Next.js SPA); all verbs use the clean `api.mangak.io`
 *    JSON (home = `/titles/home` root `data.latest.items`, featured = the same call root `data.popular` — a
 *    BARE LIST, not `{items:[…]}`, fixed 2026-06-07; search, details `/titles/{id}`, chapters
 *    `/titles/{id}/chapters`) keyed by the internal id; reader pages use the `__NEXT_DATA__` script-JSON island.
 *    **Documented limitation:** `/titles/home` is a FIXED 24-item landing payload — it ignores `?page`/`?cursor`
 *    (both return page 1), and no page-number latest endpoint was discoverable, so Home has no in-app load-more
 *    (latest is still correct/fresh). The API exposes a `next_cursor`, but no reachable endpoint honors it.
 *  - **Team X** — `featured` is the homepage popular swiper carousel (`div.swiper-slide:has(.entry-title a)`
 *    via `featured.item.*` overrides) — the genuine ranked feed the native popular parser used, NOT a clone
 *    of the latest grid (the prior config duplicated `home`; fixed 2026-06-07 after live review).
 *  - **3asq / DilarV2** — `featured` now points at each site's genuine popular ranking the native app never
 *    wired (native `popularUrl` was the homepage root / empty → an empty list): 3asq
 *    `/manga/page/{page}/?m_orderby=views` (all-time-views grid), DilarV2 `/api/series/popular` (paginated
 *    ranking). Both live-verified 2026-06-07; reuse the home `item.*` fields.
 *  - **SwatManga** — chapters paginate over the DRF `next` cursor (`page_size` server-capped at 200, so a
 *    single fetch silently dropped any chapter past #200). `chapters` now loops `?page={page}&page_size=200`
 *    with `lastPageLocator:"next"` (the engine treats a non-empty/non-boolean/non-numeric locator value — the
 *    `next` URL — as has-next; null/absent → stop). Fixed 2026-06-07 (final verification).
 *  - **Mangamello / Mangamello Plus** — `detail.status` reads `data.status` (3 → مكتمل, else مستمر). The legacy
 *    `data.is_completed` field was removed from the live API (now always null → every title showed مستمر);
 *    fixed 2026-06-07 against live payloads (Solo Leveling status=3 completed, Magic Emperor status=2 ongoing).
 *  - **Tapas** — mixed transport (JSON home/featured on story-api: `NEWEST_EPISODE` vs `ranking`, HTML
 *    search, HTML details + JSON episodes). search listSelector is `a.thumb-wrap[data-series-id]` (the
 *    image-bearing anchor) — `a[data-series-id]` also matched the duplicate text anchor, emitting blank shells
 *    (fixed 2026-06-07).
 *
 * Sources intentionally kept FULLY legacy (not config-convertible under the no-source-specific-Kotlin rule;
 * the fallback serves them — see `AR_SOURCES_CONVERSION_PLAN.md`):
 *  - **Dilar** — AES-encrypted search payload + embedded-JSON-in-HTML reader pages.
 *  - **مانجا بارك / Mangapark (AR + EN)** — GraphQL POST bodies (verb distinguished by query, not URL).
 *  - **Promanga / Prochan** — reader pages are canvas de-scrambled; covers use a per-item CDN host; Cloudflare-gated.
 *  - **Mangatuk** — rebuilt to a client-rendered Next.js/RSC SPA whose data is in React-Flight streams (not a
 *    clean JSON island), and no public JSON API was recoverable.
 *  - **Lavatoons** — Cloudflare-blocked (unverifiable) + inline-JS (`ts_reader.run`) reader pages.
 *  - **Comick** — legacy host `api.comick.fun` is dead (NXDOMAIN); live hosts (`api.comick.io`/`.dev`) are Cloudflare-gated.
 *  - **Manhwatop / Batcave** — Cloudflare-blocked (couldn't verify selectors). **Batoto** — unreachable from the build host.
 */
