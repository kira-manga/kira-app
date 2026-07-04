package me.manga.yamiapk.presentation.features.library.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R


@Composable
fun EmptyLibraryPlaceholder(libraryName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector =Icons.Outlined.Inbox
            ,
            contentDescription = stringResource(R.string.empty_library_desc,libraryName),
            modifier = Modifier
                .size(72.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_library_message,libraryName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}







//
//@Composable
//fun EmptyLibraryPlaceholder(
//    tabIndex: Int,
//    filterTabs: List<LibraryViewModel.FilterTabs>,
//    onTabChanged: (LibraryViewModel.FilterTabs) -> Unit,
//    onIndexChanged: (Int) -> Unit,
//
//    context : Context
//) {
//    Column(
//        modifier = Modifier
//            .fillMaxSize()
//            .padding(32.dp),
////        verticalArrangement = Arrangement.Center,
//        horizontalAlignment = Alignment.CenterHorizontally
//    ) {
//        TabRow(
//            selectedTabIndex = tabIndex,
//            containerColor = Color.Transparent,
//            contentColor = MaterialTheme.colorScheme.primary,
//        ) {
//            filterTabs.forEachIndexed { index, tab ->
//                Tab(
//                    selected = tabIndex == index,
//                    onClick = {
//                        onTabChanged(tab)
//                    },
//                    text = {
//                        AutoSubtitleText(
//                            text = tab.getDisplayName(context),
//                            fontSize = 14.sp,
//                            maxLines = 1,
//                            maxSize = 14.sp,
//                            color = MaterialTheme.colorScheme.primary
//                        )
//                    }
//                )
//            }
//        }
//
//        Spacer(modifier = Modifier.height(48.dp))
//
//        Icon(
//            imageVector = Icons.Outlined.Inbox,
//            contentDescription = stringResource(R.string.empty_library_desc),
//            modifier = Modifier.size(72.dp),
//            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
//        )
//        Spacer(modifier = Modifier.height(16.dp))
//        Text(
//            text = stringResource(R.string.empty_library_message),
//            style = MaterialTheme.typography.titleLarge,
//            fontWeight = FontWeight.Bold,
//            color = MaterialTheme.colorScheme.onBackground
//        )
//    }
//}
