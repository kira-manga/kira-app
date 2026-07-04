package me.manga.yamiapk.presentation.features.language.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Scaffold
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneOutline
import androidx.compose.material.rememberScaffoldState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.componants.dialogs.FeedbackDialog
import me.manga.yamiapk.presentation.common.componants.list_items.StatsItem
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.viewmodes.ComplaintViewModel
import me.manga.yamiapk.presentation.features.language.data.LanguageOption
import me.manga.yamiapk.presentation.features.language.ui.viewmodel.LanguageViewModel
import java.util.Locale


@Composable
fun LanguageSelectionScreen(
    availableLanguages: List<LanguageOption>,
    viewModel: LanguageViewModel = hiltViewModel(),
    complaintViewModel: ComplaintViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val selectedLanguage by viewModel.selectedLanguageFlow.collectAsState(
        initial = Locale.getDefault().language
    )
    var showFeedbackDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBarCom(
                title = stringResource(R.string.select_language),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                items(availableLanguages) { lang ->
                    StatsItem(
                        title = lang.displayName,
                        description = lang.code,
                        icon = if (lang.code == selectedLanguage) Icons.Default.Done else null,
                        onClick = { viewModel.selectLanguage(lang.code) }
                    )
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                }

                item {
                    StatsItem(
                        title = stringResource(R.string.request_language),
                        icon = Icons.Default.Add,
                        onClick = {
                            showFeedbackDialog = true
                        }
                    )
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            FeedbackDialog(
                visible = showFeedbackDialog,
                selectedType = ComplaintType.LANGUAGES,
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
                headerText = stringResource(R.string.request_add_language),
                textFieldText = stringResource(R.string.enter_your_language)
            )
        }
    }
}



/**
 * A single language row with a radio button and display text.
 */
@Composable
fun LanguageOptionItem(
    lang: LanguageOption,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = lang.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
