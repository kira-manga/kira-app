//package me.manga.yamiapk.presentation.features.download.ui.components
//
//
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.*
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.collectAsState
//import androidx.compose.runtime.getValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import androidx.hilt.navigation.compose.hiltViewModel
//import me.manga.yamiapk.core.cbz.CbzConversionViewModel
//import me.manga.yamiapk.presentation.features.settings.ui.components.CbzConversionDialog
//
//@Composable
//fun DownloadSettingsSection(
//    viewModel: CbzConversionViewModel = hiltViewModel()
//) {
//    // Get flows from ViewModel (which delegates to DataStoreHelper)
//    val useCbz by viewModel.useCbzFormatFlow.collectAsState(initial = true)
//    val autoConvert by viewModel.autoConvertToCbzFlow.collectAsState(initial = false)
//    val conversionProgress by viewModel.conversionProgress.collectAsState()
//
//    Column(modifier = Modifier.padding(16.dp)) {
//        Text(
//            text = "Download Settings",
//            style = MaterialTheme.typography.headlineSmall,
//            modifier = Modifier.padding(bottom = 16.dp)
//        )
//
//        // CBZ Format Switch
//
//
//        Spacer(modifier = Modifier.height(8.dp))
//
//        // Auto-convert Switch (only show if CBZ is enabled)
//        if (useCbz) {
//            SwitchItem(
//                title = "Auto-convert on Download",
//                subtitle = "Automatically convert new downloads to CBZ",
//                checked = autoConvert,
//                onCheckedChange = { viewModel.setAutoConvertToCbz(it) }
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//            Divider()
//            Spacer(modifier = Modifier.height(16.dp))
//
//            // Convert Existing Downloads Button
//            Text(
//                text = "Convert Existing Downloads",
//                style = MaterialTheme.typography.titleMedium,
//                modifier = Modifier.padding(bottom = 8.dp)
//            )
//
//            Text(
//                text = "Convert all previously downloaded chapters to CBZ format. This will save storage space but may take some time.",
//                style = MaterialTheme.typography.bodySmall,
//                color = MaterialTheme.colorScheme.onSurfaceVariant,
//                modifier = Modifier.padding(bottom = 12.dp)
//            )
//
//            Button(
//                onClick = { viewModel.startConversion() },
//                modifier = Modifier.fillMaxWidth(),
//                enabled = !conversionProgress.isConverting
//            ) {
//                if (conversionProgress.isConverting) {
//                    CircularProgressIndicator(
//                        modifier = Modifier.size(20.dp),
//                        strokeWidth = 2.dp,
//                        color = MaterialTheme.colorScheme.onPrimary
//                    )
//                    Spacer(modifier = Modifier.width(8.dp))
//                    Text("Converting...")
//                } else {
//                    Text("Start Conversion")
//                }
//            }
//        }
//    }
//
//    // Show conversion dialog
//    CbzConversionDialog(
//        progress = conversionProgress,
//        onDismiss = { viewModel.clearError() }
//    )
//}
//
//@Composable
//fun SwitchItem(
//    title: String,
//    subtitle: String? = null,
//    checked: Boolean,
//    enabled: Boolean = true,
//    onCheckedChange: (Boolean) -> Unit
//) {
//    Row(
//        modifier = Modifier
//            .fillMaxWidth()
//            .clickable(enabled = enabled) { onCheckedChange(!checked) }
//            .padding(vertical = 12.dp),
//        verticalAlignment = Alignment.CenterVertically
//    ) {
//        Column(modifier = Modifier.weight(1f)) {
//            Text(
//                text = title,
//                style = MaterialTheme.typography.bodyLarge,
//                color = if (enabled) MaterialTheme.colorScheme.onSurface
//                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
//            )
//            if (subtitle != null) {
//                Spacer(modifier = Modifier.height(4.dp))
//                Text(
//                    text = subtitle,
//                    style = MaterialTheme.typography.bodySmall,
//                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
//                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
//                )
//            }
//        }
//        Switch(
//            checked = checked,
//            onCheckedChange = onCheckedChange,
//            enabled = enabled
//        )
//    }
//}
