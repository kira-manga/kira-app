package me.manga.yamiapk.crash


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import me.manga.yamiapk.R


@Composable
fun CrashScreen(
    exception: Throwable?,
    onRestartClick: () -> Unit,
) {

    LaunchedEffect(Unit) {
        FirebaseCrashlytics.getInstance().apply {

            exception?.let { recordException(it) }
            // optional: force a synchronous upload
            sendUnsentReports()
        }
    }
    InfoScreen(
        icon = Icons.Outlined.BugReport,
        headingText = stringResource(R.string.crash_screen_title),
        subtitleText = stringResource(
            R.string.crash_screen_description,
            stringResource(R.string.app_name)
        ),
        acceptText = stringResource(R.string.pref_dump_crash_logs),
        onAcceptClick = onRestartClick,
        rejectText = stringResource(R.string.crash_screen_restart_application),
        onRejectClick = onRestartClick
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = exception.toString(),
                modifier = Modifier
                    .padding(all = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun CrashScreenPreview() {
//    TachiyomiPreviewTheme {
        CrashScreen(exception = RuntimeException("Dummy")) {}
//    }
}





