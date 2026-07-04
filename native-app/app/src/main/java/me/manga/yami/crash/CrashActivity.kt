package me.manga.yamiapk.crash


import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import me.manga.yamiapk.theme.YamiMangaTheme

class CrashActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_STACKTRACE = "extra_stacktrace"

        fun start(context: Context, stacktrace: String) {
            val intent = Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_STACKTRACE, stacktrace)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pull the serialized exception
        val trace = intent.getStringExtra(EXTRA_STACKTRACE) ?: "No trace"

        setContent {
            // remember the Exception so Compose can show it
            val exception = remember { RuntimeException(trace) }

            YamiMangaTheme(isSystemInDarkTheme()) {
            CrashScreen(
                exception = exception,
                onRestartClick = {
                    // Relaunch your launcher Activity
                    val launchIntent = packageManager
                        .getLaunchIntentForPackage(packageName)
                        ?.apply {
                            // Clear out everything and start a new task
                            addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }

                    // Kick off the launcher
                    launchIntent?.let { startActivity(it) }

                    // Kill this process so nothing from it lingers
                    finishAffinity()   }
            )}
        }
    }
}
