package me.manga.yamiapk.presentation.features.settings.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Divider
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.core.cbz.CbzConversionViewModel
import me.manga.yamiapk.core.util.Plus18memes.imgs1
import me.manga.yamiapk.core.util.Plus18memes.imgs2
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.common.componants.ItemsGroup
import me.manga.yamiapk.presentation.common.componants.dialogs.FeedbackDialog
import me.manga.yamiapk.presentation.common.componants.list_items.SwitchItem
import me.manga.yamiapk.presentation.common.viewmodel.ChaptersViewModel
import me.manga.yamiapk.presentation.features.complaint.viewmodes.ComplaintViewModel
import me.manga.yamiapk.presentation.features.details.domain.DialogState
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.AdultConfirmationDialog
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.MConfirmationDialog
import me.manga.yamiapk.presentation.features.reader.ui.components.reading_mode_dialog.ReadingModeDialog
import me.manga.yamiapk.presentation.features.settings.ui.components.CbzConversionDialog
import me.manga.yamiapk.presentation.features.settings.ui.components.SettingsNavigationItem
import me.manga.yamiapk.presentation.features.settings.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    chaptersViewModel: ChaptersViewModel = hiltViewModel(),
    complaintViewModel: ComplaintViewModel = hiltViewModel(),
    cbzViewModel: CbzConversionViewModel = hiltViewModel()


) {

    val useCbz by cbzViewModel.useCbzFormatFlow.collectAsState(initial = true)
    val autoConvert by cbzViewModel.autoConvertToCbzFlow.collectAsState(initial = false)
    val conversionProgress by cbzViewModel.conversionProgress.collectAsState()

    val context = LocalContext.current
    val cacheSize by settingsViewModel.cacheSize.collectAsStateWithLifecycle()
    val downloadedOnly by settingsViewModel.downloadedOnly.collectAsStateWithLifecycle(true)
    val incognito by settingsViewModel.incognito.collectAsStateWithLifecycle(true)
    val pureBlack by settingsViewModel.pureBlack.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.darkMode.collectAsStateWithLifecycle()
    val isFollowSystem by settingsViewModel.followSystem.collectAsStateWithLifecycle()

    var showReadingModeDialog by remember { mutableStateOf(false) }
    val currentReadingMode by chaptersViewModel.readingMode.collectAsStateWithLifecycle()
    var dialogState by remember { mutableStateOf(DialogState.None) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        backgroundColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Header Icon",
                    modifier = Modifier
                        .size(250.dp)
                        .padding(vertical = 24.dp)
                )
            }

            // ========== GENERAL SETTINGS ==========
            item {
                Divider(color = Color.Gray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(24.dp))

                ItemsGroup {
                    SwitchItem(
                        title = stringResource(R.string.downloaded_only_title),
                        description = stringResource(R.string.downloaded_only_desc),
                        icon = Icons.Default.CloudOff,
                        checked = downloadedOnly,
                        onCheckedChange = settingsViewModel::setDownloadedOnly
                    )

                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SwitchItem(
                        title = stringResource(R.string.incognito_mode_title),
                        description = stringResource(R.string.incognito_mode_desc),
                        icon = ImageVector.vectorResource(R.drawable.incognito_svgrepo_com),
                        checked = incognito,
                        onCheckedChange = settingsViewModel::setIncognito
                    )
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SwitchItem(
                        title = stringResource(R.string.system_theme),
                        description = stringResource(R.string.follow_system_theme),
                        icon = ImageVector.vectorResource(R.drawable.switchthemes),
                        checked = isFollowSystem,
                        onCheckedChange = settingsViewModel::toggleFollowSystem
                    )

                    if (!isFollowSystem) {
                        Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                        SwitchItem(
                            title = stringResource(R.string.theme_title),
                            description = stringResource(if (themeMode) R.string.theme_dark else R.string.theme_light),
                            icon = ImageVector.vectorResource(R.drawable.ic_day_night),
                            checked = themeMode,
                            onCheckedChange = settingsViewModel::toggleDarkMode
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))

                    SwitchItem(
                        title = stringResource(R.string.pure_black_mode_title),
                        icon = Icons.Outlined.DarkMode,
                        checked = pureBlack,
                        onCheckedChange = settingsViewModel::togglePureBlack
                    )

                    if (Admin.isAdmin) {
                        Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                        SwitchItem(
                            title = "Testing Mode",
                            tint = Color.Red.copy(0.5F),
                            icon = ImageVector.vectorResource(R.drawable.ic_plus_18),
                            checked = Admin.testingMode,
                            onCheckedChange = {
                                Admin.testingMode = !Admin.testingMode
                            }
                        )
                    }
                }
            }

            // ========== DOWNLOAD SETTINGS (CBZ) ==========
            item {
                Spacer(modifier = Modifier.height(24.dp))

                ItemsGroup {

//
                    SwitchItem(
                        title = stringResource(R.string.use_yami_compressor),
                        description = stringResource(R.string.use_yami_compressor_to_reduce_chapter_size_by_over_50_and_save_storage),
                        checked = useCbz,
                        onCheckedChange = { cbzViewModel.setUseCbzFormat(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (useCbz) {
                        Divider()
                        Spacer(modifier = Modifier.height(16.dp))

                        // Convert Existing Downloads Button
                        Text(
                            text = stringResource(R.string.compress_existing_downloads),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                            text = stringResource(R.string.compress_all_previously_downloaded_chapters_this_will_save_storage_space_but_may_take_some_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Button(
                            onClick = { cbzViewModel.startConversion() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !conversionProgress.isConverting
                        ) {
                            if (conversionProgress.isConverting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.converting))
                            } else {
                                Text(stringResource(R.string.start_conversion))
                            }
                        }
                    }

                    }
                }


            // ========== NAVIGATION SETTINGS ==========
            item {
                Spacer(modifier = Modifier.height(24.dp))
                ItemsGroup {
                    SettingsNavigationItem(
                        stringResource(R.string.feedbacks_and_complaints),
                        icon = ImageVector.vectorResource(R.drawable.ic_complaint),
                        onClick = {
                            if (Admin.isAdmin) {
                                navController.navigate(Screen.ComplaintAdmin)
                            } else {
                                navController.navigate(Screen.Complaint)
                            }
                        })
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.default_reading_mode),
                        icon = ImageVector.vectorResource(R.drawable.ic_reader_setting),
                        onClick = {
                            showReadingModeDialog = true
                        })
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.statistics),
                        icon = Icons.Outlined.QueryStats,
                        onClick = {
                            navController.navigate(Screen.Statistics)
                        })
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(stringResource(R.string.app_language), icon = Icons.Outlined.Language) {
                        navController.navigate(Screen.LanguageScreen)
                    }
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(stringResource(R.string.downloads), icon = Icons.Outlined.Download) {
                        navController.navigate(Screen.DownloadsScreen)
                    }
                }

                // ========== OTHER SETTINGS ==========
                Spacer(modifier = Modifier.height(24.dp))
                ItemsGroup {
                    SettingsNavigationItem(
                        title = stringResource(R.string.clear_cache),
                        description = stringResource(R.string.cache_used) + " $cacheSize",
                        icon = ImageVector.vectorResource(R.drawable.cache_cleaner)
                    ) {
                        settingsViewModel.clearLargeCache()
                    }
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))

                    SettingsNavigationItem(
                        title = stringResource(R.string.request_feature_bug_title),
                        description = stringResource(R.string.request_feature_bug_desc),
                        icon = Icons.AutoMirrored.Outlined.Message
                    ) { showFeedbackDialog = true }
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        title = stringResource(R.string.about),
                        description = stringResource(R.string.app_information_and_updates_contact_us_on_social_media),
                        icon = Icons.Outlined.Info
                    ) { navController.navigate(Screen.AboutScreen) }

                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))

                    SettingsNavigationItem(
                        title = stringResource(R.string.help),
                        icon = Icons.AutoMirrored.Outlined.Help
                    )
                }
            }
        }

        // ========== DIALOGS ==========
        FeedbackDialog(
            visible = showFeedbackDialog,
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
                showFeedbackDialog = false
            },
            onDismiss = {
                showFeedbackDialog = false
            },
            headerText = stringResource(R.string.request_feature_bug),
            textFieldText = stringResource(R.string.enter_your_feedback)
        )

        if (showReadingModeDialog) {
            ReadingModeDialog(
                currentMode = currentReadingMode,
                onModeSelected = { newMode ->
                    chaptersViewModel.setReadingMode(newMode)
                },
                onDismissRequest = {
                    showReadingModeDialog = false
                },
                onApply = {
                    showReadingModeDialog = false
                }
            )
        }

        when (dialogState) {
            DialogState.AdultWarning -> {
                AdultConfirmationDialog(
                    onConfirm = {
                        dialogState = DialogState.MStep1
                    },
                    onDismiss = {
                        dialogState = DialogState.None
                    },
                    header = stringResource(R.string.adult_filter_removal_header),
                    title = stringResource(R.string.adult_filter_removal_title)
                )
            }

            DialogState.MStep1 -> {
                MConfirmationDialog(
                    images = imgs1,
                    showContinue = true,
                    onConfirm = {
                        dialogState = DialogState.MStep2
                    },
                    onDismiss = {
                        dialogState = DialogState.None
                    }
                )
            }

            DialogState.MStep2 -> {
                MConfirmationDialog(
                    images = imgs2,
                    showContinue = false,
                    onConfirm = { /* never called */ },
                    onDismiss = {
                        dialogState = DialogState.None
                    }
                )
            }

            DialogState.None -> {}
        }

        CbzConversionDialog(
            conversionProgress = conversionProgress,
            onDismiss = {
                if (conversionProgress.isConverting) {
                    cbzViewModel.stopConversion()
                } else {
                    cbzViewModel.clearError()
                }
            }
        )
    }
}