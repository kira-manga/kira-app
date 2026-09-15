package me.manga.kira.locale

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import me.manga.kira.MyApp
import me.manga.kira.R
import me.manga.kira.core.storage.StorageKeys
import org.junit.Assert.assertEquals
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowApplication

/** Seeds real persisted input, then delegates actual MyApp.attachBaseContext without shadowing it. */
@Implements(Application::class)
class PreKoinLocaleApplicationShadow : ShadowApplication() {
    @RealObject
    private lateinit var realApplication: Application

    var witness: AttachLocaleWitness? = null
        private set

    override fun callAttach(context: Context) {
        if (realApplication !is MyApp) {
            super.callAttach(context)
            return
        }
        val rawConfiguration = Configuration(context.resources.configuration)
        check(
            context.getSharedPreferences("kira_settings", Context.MODE_PRIVATE).edit()
                .putString(StorageKeys.SELECTED_LANGUAGE, "ar").commit(),
        )
        super.callAttach(context)
        val host = realApplication as MyApp
        val firstResources = host.resources
        assertEquals("The host must not mutate its raw base", rawConfiguration, context.resources.configuration)
        witness = AttachLocaleWitness(
            host,
            rawConfiguration,
            Configuration(firstResources.configuration),
            firstResources.getString(R.string.notification_channel_library_refresh),
            GlobalContext.getOrNull() == null,
        )
    }
}

/** Values captured before Robolectric's later bootstrap updateConfiguration and before Koin. */
data class AttachLocaleWitness(
    val host: MyApp,
    val rawConfiguration: Configuration,
    val localizedConfiguration: Configuration,
    val firstResourceText: String,
    val koinWasAbsent: Boolean,
)
