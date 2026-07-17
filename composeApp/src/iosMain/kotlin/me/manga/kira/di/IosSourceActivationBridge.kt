package me.manga.kira.di

import me.manga.kira.navigation.sourceaccess.SourceActivationRequestRouter
import org.koin.mp.KoinPlatform

/** Swift-facing bridge for universal-link and custom-scheme source activation. */
fun onSourceActivationLink(link: String): Boolean =
    runCatching {
        KoinPlatform.getKoin().get<SourceActivationRequestRouter>().submit(link)
    }.getOrDefault(false)
