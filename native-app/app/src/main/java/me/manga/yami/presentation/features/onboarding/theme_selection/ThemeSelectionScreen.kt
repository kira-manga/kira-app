package me.manga.yamiapk.presentation.features.onboarding.theme_selection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.onboarding.welcome.AnimatedBackground
import me.manga.yamiapk.theme.YamiMangaTheme

// --- Theme Selection Screen with TabRow ---
@Composable
fun ThemeSelectionScreen(
    currentTheme: AppTheme = AppTheme.System,
    onThemeSelected: (AppTheme) -> Unit,
    onContinue:  () -> Unit
) {

    val context = LocalContext.current

    val hasNotificationPermission = remember {
        mutableStateOf(hasPostNotificationPermission(context))
    }
    // guard so we only auto‑request once
    val autoRequested = remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->

        if (!granted)  Toast.makeText(context,
            context.getString(R.string.you_need_to_enable_notifications), Toast.LENGTH_LONG).show()


        hasNotificationPermission.value = granted
        // if denied + don't ask again, you'll still catch it here and redirect:
        if (!granted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                context as Activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            context.openAppSettings()
        }
    }

    // Auto‑launch the permission request once when the composable first enters composition:
    LaunchedEffect(key1 = autoRequested.value) {
        if (!autoRequested.value &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission.value
        ) {
            autoRequested.value = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {

        AnimatedBackground(modifier = Modifier.fillMaxSize())

        // Gradient overlay from transparent to dark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.1f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.choose_your_theme),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.primary
                )

            )

            Spacer(modifier = Modifier.height(16.dp))

            ThemeSelector(
                themes = AppTheme.entries,
                selected = currentTheme,
                onThemeSelected = onThemeSelected,
                onRequestNotificationPermission = {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                // No runtime permission needed, directly update state
                      hasNotificationPermission.value = true
            }

                },

            )
            Button(
                onClick = onContinue,
                enabled = hasNotificationPermission.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(26.dp)),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = stringResource(R.string.continue_string),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

// --- Theme Enumeration ---
enum class AppTheme(@StringRes val displayNameRes: Int) {
    Light(R.string.theme_light),
    Dark(R.string.theme_dark    ),
    System(R.string.theme_system)
}

// Preview
@Preview(showBackground = true)
@Composable
fun ThemeSelectionPreview() {
    YamiMangaTheme(true) {
    ThemeSelectionScreen(
        currentTheme = AppTheme.System,
        onThemeSelected = {},
        onContinue = {}
    )}
}
fun hasPostNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun shouldShowRequestPermissionRationale(context: Context, permission: String): Boolean =
    if (context is Activity) {
        ActivityCompat.shouldShowRequestPermissionRationale(context, permission)
    } else {
        // fallback for non-Activity contexts
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

// extension to open app settings
private fun Context.openAppSettings() {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.also { startActivity(it) }
}