package me.manga.yamiapk.sources_repositry.ru.senkuro.models

import kotlinx.serialization.Serializable

// GraphQL wrapper
@Serializable
data class GraphQL<T>(
    val variables: T? = null,
    val query: String? = null,
    val operationName: String? = null,
) {
    @Serializable
    data class Extensions(
        val persistedQuery: PersistedQuery
    ) {
        @Serializable
        data class PersistedQuery(
            val sha256Hash: String,
            val version: Int
        )
    }
}

// New Popular Manga Variables
//@Serializable
//data class PopularMangaVariables(
//    val after: String? = null,
//    val bookmark: FilterDto = FilterDto(),
//    val chapters: EmptyFilter = EmptyFilter(),
//    val format: FilterDto = FilterDto(),
//    val label: FilterDto = FilterDto(exclude = listOf("hentai")),
//    val orderDirection: String = "DESC",
//    val orderField: String = "POPULARITY_SCORE",
//    val originCountry: FilterDto = FilterDto(),
//    val rating: FilterDto = FilterDto(),
//    val releasedOn: EmptyFilter = EmptyFilter(),
//    val search: String? = null,
//    val source: FilterDto = FilterDto(),
//    val status: FilterDto = FilterDto(),
//    val translitionStatus: FilterDto = FilterDto(),
//    val type: FilterDto = FilterDto(),
//) {
//    @Serializable
//    data class FilterDto(
//        val include: List<String> = emptyList(),
//        val exclude: List<String> = emptyList(),
//    )
//
//    @Serializable
//    class EmptyFilter
//}

// Search Variables (keeping for backward compatibility)
@Serializable
data class SearchVariables(
    val query: String? = null,
    val type: FiltersDto? = null,
    val status: FiltersDto? = null,
    val translationStatus: FiltersDto? = null,
    val genre: FiltersDto? = null,
    val tag: FiltersDto? = null,
    val format: FiltersDto? = null,
    val rating: FiltersDto? = null,
    val offset: Int? = null,
) {
    @Serializable
    data class FiltersDto(
        val include: List<String>? = null,
        val exclude: List<String>? = null,
    )
}
@Serializable
 data class PopularMangaVariables(
    val first: Int = 20,
    val after: String? = null,
    val search: String? = null,
    val orderField: String? = null,
    val orderDirection: String? = null,
    val offset: Int? = null
)
// Details Variables
@Serializable
data class FetchDetailsVariables(
    val mangaId: String? = null,
)

// Chapter Pages Variables
@Serializable
data class FetchChapterPagesVariables(
    val mangaId: String? = null,
    val chapterId: String? = null,
)

// Page Wrapper
@Serializable
data class PageWrapperDto<T>(
    val data: T? = null,
)
// New Popular Manga Response Structure
@Serializable
data class MangasConnectionDto(
    val mangas: MangaConnection
) {
    @Serializable
    data class MangaConnection(
        val edges: List<MangaEdge>,
        val pageInfo: PageInfo
    ) {
        @Serializable
        data class MangaEdge(
            val node: MangaNode
        )

        @Serializable
        data class PageInfo(
            val hasNextPage: Boolean,
            val endCursor: String? = null
        )
    }
}

@Serializable
data class MangaNode(
    val id: String,
    val slug: String,
    val originalName: I18nTitle? = null,
    val titles: List<I18nTitle> = emptyList(),
    val status: String? = null,
    val type: String? = null,
    val formats: List<String> = emptyList(),
    val rating: String? = null,
    val score: Double? = null,
    val containExplicitThemes: Boolean = false,
    val cover: CoverDto? = null,
) {
    @Serializable
    data class I18nTitle(
        val lang: String,
        val content: String
    )

    @Serializable
    data class CoverDto(
        // make id optional (server often doesn't return it)
        val id: String? = null,
        val blurhash: String? = null,
        // original could be missing in some responses, make nullable too
        val original: ImageSize? = null,
        val preview: ImageSize? = null
    ) {
        @Serializable
        data class ImageSize(
            // url might be missing in edge cases — keep it nullable
            val url: String? = null,
            val height: Int? = null,
            val width: Int? = null
        )
    }
}
// Search Response (keeping for backward compatibility)
@Serializable
data class MangaTachiyomiSearchDto<T>(
    val mangaTachiyomiSearch: MangasDto<T>,
) {
    @Serializable
    data class MangasDto<T>(
        val mangas: List<T>,
    )
}

// Manga Info
@Serializable
data class SubInfoDto(
    val mangaTachiyomiInfo: MangaTachiyomiInfoDto,
)

@Serializable
data class MangaTachiyomiInfoDto(
    val id: String,
    val slug: String,
    val cover: SubImgDto? = null,
    val status: String? = null,
    val type: String? = null,
    val rating: String? = null,
    val formats: List<String>? = null,
    val genres: List<TagsDto>? = null,
    val tags: List<TagsDto>? = null,
    val titles: List<TitleDto>,
    val alternativeNames: List<TitleDto>? = null,
    val localizations: List<LocalizationsDto>? = null,
    val mainStaff: List<MainStaffDto>? = null,
) {
    @Serializable
    data class SubImgDto(
        val original: ImgDto,
    ) {
        @Serializable
        data class ImgDto(
            val url: String? = null,
        )
    }

    @Serializable
    data class TagsDto(
        val slug: String,
        val titles: List<TitleDto>,
    )

    @Serializable
    data class TitleDto(
        val lang: String,
        val content: String,
    )

    @Serializable
    data class LocalizationsDto(
        val lang: String,
        val description: String,
    )

    @Serializable
    data class MainStaffDto(
        val roles: List<String>,
        val person: PersonDto,
    ) {
        @Serializable
        data class PersonDto(
            val name: String,
        )
    }
}

// Chapters
@Serializable
data class MangaTachiyomiChaptersDto(
    val mangaTachiyomiChapters: ChaptersMessage,
) {
    @Serializable
    data class ChaptersMessage(
        val message: String? = null,
        val chapters: List<BookDto>,
        val teams: List<TeamsDto>,
    ) {
        @Serializable
        data class BookDto(
            val id: String,
            val slug: String,
            val branchId: String,
            val name: String? = null,
            val teamIds: List<String>,
            val number: String,
            val volume: String,
            val createdAt: String,
        )

        @Serializable
        data class TeamsDto(
            val id: String,
            val slug: String,
            val name: String,
        )
    }
}

// Chapter Pages
@Serializable
data class MangaTachiyomiChapterPages(
    val mangaTachiyomiChapterPages: ChaptersPages,
) {
    @Serializable
    data class ChaptersPages(
        val pages: List<UrlDto>,
    ) {
        @Serializable
        data class UrlDto(
            val url: String,
        )
    }
}

// Search Filters
@Serializable
data class MangaTachiyomiSearchFilters(
    val mangaTachiyomiSearchFilters: FilterDto,
) {
    @Serializable
    data class FilterDto(
        val genres: List<FilterDataDto>,
        val tags: List<FilterDataDto>,
    ) {
        @Serializable
        data class FilterDataDto(
            val slug: String,
            val titles: List<TitleDto>,
        ) {
            @Serializable
            data class TitleDto(
                val lang: String,
                val content: String,
            )
        }
    }
}



data class PaginationState(
    val endCursor: String? = null,
    val hasNextPage: Boolean = true
)

 data class MangaPageResult(
    val items: List<MangaNode>,
    val paginationState: PaginationState
)