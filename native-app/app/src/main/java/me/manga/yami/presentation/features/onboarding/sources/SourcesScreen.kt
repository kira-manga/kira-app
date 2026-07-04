package me.manga.yamiapk.presentation.features.onboarding.sources

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.ItemsGroup
import me.manga.yamiapk.presentation.common.componants.dialogs.FeedbackDialog
import me.manga.yamiapk.presentation.common.componants.sources.LanguageToggleWithAnimation
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.viewmodes.ComplaintViewModel
import me.manga.yamiapk.presentation.features.onboarding.welcome.AnimatedBackground
import me.manga.yamiapk.presentation.features.repo_settings.ui.components.LanguageToggle
import me.manga.yamiapk.presentation.features.repo_settings.ui.screens.removeAllParens
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel
import me.manga.yamiapk.presentation.features.settings.ui.components.SettingsNavigationItem
import java.util.Locale

@SuppressLint("LocalContextConfigurationRead")
@Composable
fun SourcesScreen(
    repoSettingsViewModel: RepoSettingsViewModel,
    complaintViewModel : ComplaintViewModel = hiltViewModel(),
    onFinish:  () -> Unit
) {
    val context = LocalContext.current

    val enabledStates by repoSettingsViewModel.enabledStates.collectAsStateWithLifecycle()
    val grouped = repoSettingsViewModel.groupedByLanguage()
    var showSourceDialog by remember { mutableStateOf(false) }
    val configuration = context.resources.configuration
    val locale = configuration.locales[0]
    val languageCode = locale.language
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        repoSettingsViewModel.setLanguageEnabledDefault("(${languageCode.uppercase(Locale.ROOT)})",true)
    }

    // Use Scaffold instead of Surface
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background // Set background color here
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                    text = stringResource(R.string.select_your_manga_sources),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1F)
                ) {
                    item {
                        ItemsGroup(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4F)) {
                            SettingsNavigationItem(
                                stringResource(R.string.request_adding_source),
                                stringResource(R.string.enter_the_url_for_site_you_want_us_to_add),
                                icon = Icons.Outlined.AddCircleOutline
                            ) {
                                showSourceDialog = true
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    item {
                        ItemsGroup(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4F)) {
                            SettingsNavigationItem(
                                stringResource(R.string.languages_coming_soon_title),
                                stringResource(R.string.languages_coming_soon_description),
                                icon = Icons.Outlined.Info,
                                iconColor = MaterialTheme.colorScheme.error,
                                endIcon = null,
                                maxLines = 3,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    grouped.forEach { (language, repos) ->
                        item {
                            ItemsGroup(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4F)) {
                                LanguageToggle(
                                    language = getLanguageName(
                                        language.removeAllParens()
                                            .lowercase(Locale.ROOT)
                                    ),
                                    repos = repos,
                                    enabledStates = enabledStates,
                                    onToggleLanguage = { repoSettingsViewModel.setLanguageEnabled(language, it) },
                                    description = stringResource(
                                        R.string.enable_disable_all_sources,
                                        language
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LanguageToggleWithAnimation(
                                    repos = repos,
                                    enabledStates = enabledStates,
                                    onToggleLanguage = { api, bol ->
                                        repoSettingsViewModel.setRepoEnabled(api, bol)
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }

                FeedbackDialog(
                    visible = showSourceDialog,
                    selectedType = ComplaintType.SITES_ADD,
                    onSubmit = { type, body ->
                        type?.let {
                            complaintViewModel.submit(
                                it,
                                it.getDisplayName(context),
                                body,
                                onSuccess = { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.request_submitted_successfully),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                },
                                onError = { error ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.request_failed),
                                            actionLabel = context.getString(R.string.retry),
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                }
                            )
                        }
                        showSourceDialog = false
                    },
                    onDismiss = {
                        showSourceDialog = false
                    },
                    headerText = stringResource(R.string.we_will_add_it_as_soon_it_possible),
                    textFieldText = stringResource(R.string.enter_the_site_url)
                )

                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(26.dp)),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.finish),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }
    }
}

fun getLanguageName(
    code: String,
    inLocale: Locale = Locale.getDefault()
): String {
    return try {
        // create a Locale just for retrieving the name
        Locale(code).getDisplayLanguage(inLocale).replaceFirstChar { it.uppercase(inLocale) }
    } catch (e: Exception) {
        // fallback to the raw code if something goes wrong
        code
    }
}