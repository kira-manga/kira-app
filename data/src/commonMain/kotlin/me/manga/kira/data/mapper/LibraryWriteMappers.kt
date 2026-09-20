package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.SavedMangaClassificationMetadata
import me.manga.kira.data.local.entity.SavedMangaContentMetadata
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.local.entity.SavedMangaMetadataUpdate
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails

internal fun SavedMangaEntity.savedIdentity() = SavedWorkIdentity(id, WorkLocator(api, url))

internal fun FetchedWorkDetails.toNewLibraryEntity(now: Long) = SavedMangaEntity(
    api = requested.api,
    language = details.language,
    url = requested.url,
    imageUrl = details.coverUrl,
    title = details.title,
    description = details.description,
    author = details.author,
    status = details.status,
    rating = details.rating,
    genres = details.genres,
    savedTimestamp = now,
    lastOpenTimestamp = now,
)

internal fun MangaDetails.metadataUpdate(owner: SavedMangaEntity) = SavedMangaMetadataUpdate(
    id = owner.id,
    content = SavedMangaContentMetadata(
        title = title,
        description = description,
        author = author,
        imageUrl = coverUrl.ifBlank { owner.imageUrl },
    ),
    classification = SavedMangaClassificationMetadata(
        language = language,
        status = status,
        rating = rating,
        genres = genres,
    ),
)
