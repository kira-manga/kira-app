package me.manga.yamiapk.presentation.features.about.screen


import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material.icons.outlined.AppRegistration
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.R
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.common.componants.ItemsGroup
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.features.about.common.SocialMediaRow
import me.manga.yamiapk.presentation.features.about.common.icons.CustomIcons
import me.manga.yamiapk.presentation.features.about.common.icons.Discord
import me.manga.yamiapk.presentation.features.about.common.icons.X
import me.manga.yamiapk.presentation.features.about.common.openAppInPlayStore
import me.manga.yamiapk.presentation.features.about.common.openBrowser
import me.manga.yamiapk.presentation.features.about.common.openFacebook
import me.manga.yamiapk.presentation.features.about.common.openInstagram
import me.manga.yamiapk.presentation.features.about.common.openTwitter
import me.manga.yamiapk.presentation.features.about.common.sendWhatsAppMessage
import me.manga.yamiapk.presentation.features.settings.ui.components.SettingsNavigationItem
import me.manga.yamiapk.presentation.features.whatsnew.viewmodel.WhatsNewViewModel

@Composable
fun AboutScreen(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    whatsNewViewModel: WhatsNewViewModel ,
    onBack: () -> Unit

) {


    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBarCom(
                title = stringResource(R.string.about),
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
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { paddingValues ->


        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background) // Dark background
                .padding(start = 16.dp, end = 16.dp, top = paddingValues.calculateTopPadding()),

            horizontalAlignment = Alignment.CenterHorizontally

        ) {

            item {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground), // Your drawable image
                    contentDescription = "Header Icon",
                    modifier = Modifier
                        .size(250.dp) // Make it big
                        .padding(vertical = 24.dp)
                )
            }
            item {
                Divider(color = Color.Gray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(24.dp))
                ItemsGroup {
                    SettingsNavigationItem(
                        stringResource(R.string.version),
                        "${context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_ACTIVITIES).versionName   }",
                        icon = Icons.Outlined.AppRegistration,
                     )
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.check_for_update),
                        icon = Icons.Outlined.Update,
                        ){
                        openAppInPlayStore(context)
                    }

                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.rate_our_app),
                        icon = Icons.Outlined.StarRate,
                    ){
                        openAppInPlayStore(context)
                    }

                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.what_s_new), icon = Icons.AutoMirrored.Outlined.ManageSearch
                    ){
                        whatsNewViewModel.ensureFeaturesLoaded()
                        navController.navigate(Screen.WhatsNewScreen(false))
                    }
                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(
                        stringResource(R.string.source_code),
                        stringResource(R.string.soon),
                        icon = Icons.Outlined.Code
                    )

                    Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    SettingsNavigationItem(stringResource(R.string.privacy_policy)){openBrowser(context,"https://yamimanga.me/privacy")}
                }

                Spacer(modifier = Modifier.height(24.dp))

                SocialMediaRow()

                Spacer(modifier = Modifier.height(24.dp))

            }


        }


    }


}









