package me.manga.yamiapk.presentation.features.repo_settings.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.ItemsGroup
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.componants.dialogs.FeedbackDialog
import me.manga.yamiapk.presentation.common.componants.sources.LanguageToggleWithAnimation
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.viewmodes.ComplaintViewModel
import me.manga.yamiapk.presentation.features.repo_settings.ui.components.LanguageToggle
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel
import me.manga.yamiapk.presentation.features.settings.ui.components.SettingsNavigationItem

@Composable
fun RepoSettingsScreen(
    isFirstOpen: Boolean,
    complaintViewModel: ComplaintViewModel = hiltViewModel(),
    viewModel: RepoSettingsViewModel,
    onFinish: () -> Unit,
    onBackPress: () -> Unit
) {
    val context = LocalContext.current
    val enabledStates by viewModel.enabledStates.collectAsStateWithLifecycle()
    val grouped = viewModel.groupedByLanguage()
    var showSourceDialog by remember { mutableStateOf(false) }
    var sourceText by remember { mutableStateOf("") }

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBarCom(
                title = stringResource(R.string.title_sources_settings),
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (isFirstOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
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
                            text = "Finish",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        },
        backgroundColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                ItemsGroup {
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
                ItemsGroup {
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
                    ItemsGroup {
                        LanguageToggle(
                            language = language.removeAllParens(),
                            repos = repos,
                            enabledStates = enabledStates,
                            onToggleLanguage = { viewModel.setLanguageEnabled(language, it) },
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
                                viewModel.setRepoEnabled(api, bol)
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
    }
}

fun String.removeAllParens(): String =
    this.replace("(", "")
        .replace(")", "")