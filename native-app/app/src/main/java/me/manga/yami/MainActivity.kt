package me.manga.yamiapk


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.InitializationStatus
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.google_play_cores.app_review.ReviewManagerHelper
import me.manga.yamiapk.google_play_cores.app_update.AppUpdateHelper
import me.manga.yamiapk.navigation.NavGraphV2
import me.manga.yamiapk.presentation.common.componants.BottomNavigationBar
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.repo_settings.domain.UpdateSourcesRepository
import me.manga.yamiapk.presentation.features.settings.ui.viewmodel.SettingsViewModel
import me.manga.yamiapk.theme.YamiMangaTheme
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import android.util.Log
import kotlinx.serialization.json.jsonArray

import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.admin.api_test.ApiTestScreen
import me.manga.yamiapk.dex.DexPluginLoader
import me.manga.yamiapk.dex.DexPluginLoader.loadPlugin
import me.manga.yamiapk.dex.MangaSourceConfig
import me.manga.yamiapk.dex.PluginConfig

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var reviewHelper: ReviewManagerHelper
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    @Inject
    lateinit var api: IMangaDataApiServices

    @Inject
    lateinit var updateSources: UpdateSourcesRepository

    private val reviewScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }

    private lateinit var consentInformation: ConsentInformation
    private var consentForm: ConsentForm? = null
        fun runPluginHtml(context: Context, html: String): Any? {
        return try {
            val plugin = loadPlugin(context, "PluginData.dex")

            val method = plugin::class.java.getMethod("parseFromHtml", String::class.java)

            try {
               val text = method.invoke(plugin, html)
                Log.e("PLUGIN_HTML", "data is $text ")

                text
            } catch (invokeError: Exception) {
                Log.e("PLUGIN_HTML", "Failed to invoke parseFromHtml()", invokeError)
                null
            }

        } catch (loaderError: Exception) {
            Log.e("PLUGIN_HTML", "Failed to load PluginData.dex or method not found", loaderError)
            null
        }
    }



    fun runPluginList(context: Context, jsonList: String) {

        try {
            val array = org.json.JSONArray(jsonList)

            for (i in 0 until array.length()) {
                try {
                    val itemJson = array.getJSONObject(i).toString()

                    val result = DexPluginLoader.runPluginJson(context, itemJson)

                   val resut2 = result as MangaSourceConfig
                    Log.e("PLUGIN_RESULT", "Item #$i → $result ========= $resut2")

                } catch (itemError: Exception) {
                    Log.e("PLUGIN_ERROR", "Failed to parse item #$i", itemError)
                }
            }

        } catch (listError: Exception) {
            Log.e("PLUGIN_FATAL", "Invalid JSON list passed to runPluginList", listError)
        }
    }


    fun runPluginGetConfig(context: Context, dexName: String = "PluginData.dex"): PluginConfig? {
        return try {
            // Load DEX plugin
            val pluginClass = loadPlugin(context, dexName)?.javaClass
                ?: throw IllegalStateException("Plugin class not loaded")

            // INSTANCE field
            val instanceField = pluginClass.getDeclaredField("INSTANCE")
            val instance = instanceField.get(null)

            // getConfig() method
            val method = pluginClass.getMethod("getConfig")

            // Invoke
            val result = method.invoke(instance) as PluginConfig

            Log.e("PLUGIN_CONFIG", "Config Loaded → $result")

            result

        } catch (e: ClassNotFoundException) {
            Log.e("PLUGIN_CONFIG", "Plugin class not found", e)
            null
        } catch (e: NoSuchMethodException) {
            Log.e("PLUGIN_CONFIG", "getConfig() not found", e)
            null
        } catch (e: Exception) {
            Log.e("PLUGIN_CONFIG", "Error invoking getConfig()", e)
            null
        }
    }

    private val updateType = false // if it IMMEDIATE or FLEXIBLE
    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
//        val jsonList = """
//<!DOCTYPE html>
//<html lang="en">
//<head>
//    <meta charset="UTF-8">
//
//    <!-- Basic Plugin Metadata -->
//    <meta name="source-name" content="AdvancedMangaSource">
//    <meta name="base-url" content="https://bigmangasite.com">
//    <meta name="chapters-path" content="/series/%id%/chapters/list">
//    <meta name="manga-path" content="/series/%id%/overview">
//
//    <!-- Header Fields -->
//    <meta name="header-User-Agent" content="BigMangaApp/5.3 (Android)">
//    <meta name="header-Accept-Language" content="en-US,en;q=0.9">
//    <meta name="header-Cookie" content="session_id=abc123xyz; theme=dark">
//    <meta name="header-Authorization" content="Bearer TOKEN_987654321">
//    <meta name="header-X-App-Version" content="5.3.2">
//    <meta name="header-X-Client" content="Mobile-Android">
//
//    <!-- Extra Optional Fields -->
//    <meta name="preview-image" content="https://bigmangasite.com/images/sample-cover.jpg">
//    <meta name="description" content="A large HTML document used for testing Jsoup parsing in dynamic plugin loaders.">
//
//    <!-- Many Custom Headers -->
//    <meta name="header-Cache-Control" content="no-cache">
//    <meta name="header-Api-Key" content="SUPER_SECRET_KEY_123456">
//    <meta name="header-Device-ID" content="device-88A1F3">
//    <meta name="header-Timezone" content="Africa/Cairo">
//    <meta name="header-Geo" content="EG">
//    <meta name="header-Mode" content="production">
//    <meta name="header-Theme" content="black">
//    <meta name="header-Platform" content="Android14">
//    <meta name="header-Network" content="WiFi">
//
//    <!-- Fake SEO to make HTML heavy -->
//    <meta name="keywords" content="manga, comics, chapters, jsoup, plugin, dex">
//    <meta name="robots" content="noindex, nofollow">
//    <meta name="author" content="PluginTech">
//
//    <title>Big Manga Source Test Document</title>
//</head>
//
//<body>
//    <h1>Advanced Manga Source</h1>
//
//    <p>This is a large HTML document used to test dynamic plugin parsing with Jsoup.</p>
//
//    <!-- Fake content to enlarge size -->
//    <div class="content-block">
//        <p>Lorem ipsum dolor sit amet, consectetur adipiscing elit. Donec nec risus id lacus suscipit luctus.</p>
//        <p>Quisque ac magna quis elit feugiat pretium non nec urna.</p>
//    </div>
//
//    <div class="chapter-preview">
//        <img src="https://bigmangasite.com/img/ch1.jpg" alt="chapter 1 img">
//        <p>Preview of chapter 1.</p>
//    </div>
//
//    <footer>
//        <p>Generated at: 2025-12-04</p>
//        <p>For Plugin Debugging Only</p>
//    </footer>
//</body>
//</html>
//"""


//        runPluginGetConfig(this)

        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.APP_OPEN, Bundle())

        updateSources.initializeSources()
        // Trigger update check (flexible by default)
        AppUpdateHelper.checkForUpdate(
            activity = this,
            immediate = updateType,
            onUpdateNotAvailable = {
                Log.d("MainActivity", "No update available")
            },
            onError = { exception ->
                Log.e("MainActivity", "Update check failed", exception)
                // Handle error gracefully - don't crash the app
            }
        )
        CoroutineScope(Dispatchers.IO).launch {
            // Initialize the Google Mobile Ads SDK on a background thread.
            requestConsent(this@MainActivity)

            //        var isAdRequested by remember { mutableStateOf(false) }
//        val testDeviceIds = Arrays.asList("3C73A846DBB6F54FEFB3721F374CCD59", AdRequest.DEVICE_ID_EMULATOR)
//        val configuration = RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
//        MobileAds.setRequestConfiguration(configuration)

        }
        reviewScope.launch {
            launchInAppReviewSafely()
        }


        AppUpdateHelper.registerListener(
            onProgress = { downloaded, total ->
                val safeTotal = total.coerceAtLeast(1L)
                val percent   = (downloaded * 100 / safeTotal).toInt()
                // update your ProgressBar/TextView here…
            }, onDownloaded = {
                // 1) Tell Play Core to finish the install
                AppUpdateHelper.completeUpdate()
            }
        )



        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val system by settingsViewModel.followSystem.collectAsState()
//            val com: ComplaintViewModel = hiltViewModel()

//            var firstLaunch by PrefsDelegate(
//                context    = this,
//                key        = "first_launch",
//                defaultValue = true
//            )
//            firstLaunch = true
            val dark by settingsViewModel.darkMode.collectAsState()
            val pureBlack by settingsViewModel.pureBlack.collectAsState()
//            LaunchedEffect(Unit) {
                // give Compose a moment to draw
//                delay(100)
//                withContext(Dispatchers.IO) {
//                    requestNotificationPermissionIfNeeded()
//                }
//            }
            // remove splash once theme values emit

            YamiMangaTheme(
                darkTheme = if (system) isSystemInDarkTheme() else dark,
                pureBlack = pureBlack
            ) {

                if (Admin.testingMode) {
                    val coroutineScope = rememberCoroutineScope()
                    val context = LocalContext.current

//                    Box(modifier = Modifier
//                        .fillMaxSize()
//                        .padding(16.dp)) {
//                        Column(modifier = Modifier.fillMaxSize()) {
//                            Text(text = "SEND", style = MaterialTheme.typography.titleLarge)
//
//                            Button(onClick = {
//                                coroutineScopex.launch {
//                    try {
//                                    // run network on IO
//                                    val response = withContext(Dispatchers.IO) {
//                                        fetchBatcavePage(api = api)
//                                    }
//                        response.headers().forEach { cookie ->
//                            Log.i("asdasdsadasfdffgfgdNET", "Set-Cookie: $cookie")
//                        }
//
//                        Log.i("sdgjsfgdfgdfsgsdgsdfgdsfs1",response.toString())
//                        Log.i("sdgjsfgdfgdfsgsdgsdfgdsfs2",response.body().toString())
//
//                        responseText = response.body().toString()
//                                    // inspect response safely on Main
//                                    withContext(Dispatchers.Main) {
//                                        if (response.isSuccessful) {
//                                            val body = response.body()
//                                            responseText = body ?: "empty response body"
//                                            body?.let { text ->
//                                                try {
//                                                    // share the file (will create it in cacheDir and open share chooser)
//                                                    shareTextFile(context, "det.txt", text)
//                                                } catch (e: Exception) {
//                                                    responseText = "Failed to save/send file: ${e.message}"
//                                                    Log.e("fetch", "save/share failed", e)
//                                                }
//                                            }
//                                        } else {
//                                            val code = response.code()
//                                            val err = response.errorBody()?.string() ?: "no error body"
//                                            responseText = "HTTP $code: $err"
//                                        }
//                                    }
//                                } catch (e: Exception) {
//                                // avoid crashing the UI thread — show the error instead
//                                withContext(Dispatchers.Main) {
//                                    responseText = "Request failed: ${e.message}"
//                                }
//                                Log.e("afsdvcdjfgfghaufghfogdf", "fetch error", e)
//                            }
//                                }
//                            }) {
//                                Text(text = "plus")
//                            }
//
//
//                            Spacer(modifier = Modifier.height(16.dp))
//
//                            Text(
//                                text = responseText,
//                                color = androidx.compose.ui.graphics.Color.Red,
//                                modifier = Modifier
//                                    .verticalScroll(scrollState)
//                                    .weight(1f)
//                                    .fillMaxWidth(),
//                                style = MaterialTheme.typography.bodyMedium
//                            )
//                        }
//                    }

                    ApiTestScreen(api,context,coroutineScope)
                }
                else {
                    MainScreen()
                }
            }
        }

    }



    fun requestConsent(activity: Activity) {
        consentInformation = UserMessagingPlatform.getConsentInformation(activity)

//        val debugSettings = ConsentDebugSettings.Builder(activity)
////            // Force the SDK to treat this device as if it were in the EEA
//            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
////            // (Optional) Add your device’s hashed ID so you don’t affect real analytics.
////            // Replace with the ID you see in your logcat when you first call requestConsentInfoUpdate().
////            .addTestDeviceHashedId("B3EEABB8EE11C2BE770B684D95219ECB")
//            .addTestDeviceHashedId("B3EEABB8EE11C2BE770B684D95219ECB")
//
//
//            .build()

        // 2) Create request parameters
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)  // set true if targeting under‑age users
//            .setConsentDebugSettings(debugSettings)

            .build()
        Log.d(
            "logAdapterStatus",
            "Adapter",
        )
//        consentInformation.reset()
        // 3) Request an update
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Success

                if (consentInformation.isConsentFormAvailable) {
                    loadAndShowForm(activity)
                } else {
                    // Consent not required—proceed to load ads
                    initializeAds(activity)
                }
            },
            { formError ->
                initializeAds(activity)

            }
        )
    }

    private fun logAdapterStatus(initializationStatus: InitializationStatus) {
        val map = initializationStatus.adapterStatusMap
        if (map.isEmpty()) {
            Log.d("ADS_INIT", "adapterStatusMap is empty")
            return
        }

        for ((adapterClass, status) in map) {
            val desc = status.description.takeIf { it.isNotBlank() } ?: "no description"
            // Depending on SDK version the property name may be `initializationState` or `state`
            val state = try {
                status.initializationState.toString()
            } catch (e: Throwable) {
                try { status.toString() } catch (_: Throwable) { "unknown" }
            }
            Log.d(
                "ADS_INIT",
                "Adapter: $adapterClass, State: $state, Description: $desc, Latency: ${status.latency}ms"
            )
        }
    }

    private fun loadAndShowForm(activity: Activity) {
        UserMessagingPlatform.loadConsentForm(
            activity,
            { form ->

                consentForm = form

                when (consentInformation.consentStatus) {
                    ConsentInformation.ConsentStatus.REQUIRED -> {
                        // Show the form
                        form.show(activity) { formError ->
                            // After the user makes a choice, you can now load ads
                            initializeAds(activity)
                        }
                    }
                    else -> {
                        // Consent already obtained or not required
                        initializeAds(activity)
                    }
                }
            },
            { loadError      ->

                initializeAds(activity)
            }
        )
    }

    private fun initializeAds(context: Context) {
        // Check if you’re allowed to request ads:
        if (consentInformation.canRequestAds()) {
            MobileAds.initialize(context) {initializationStatus ->
                logAdapterStatus(initializationStatus)

            }
        }
    }

    private suspend fun launchInAppReviewSafely() {
        // call the suspend fun from a coroutine
//        lifecycleScope.launch(Dispatchers.IO) {
            try {
                reviewHelper.launchInAppReview(this@MainActivity)
            } catch (e: Exception) {
                // optionally log or swallow
                e.printStackTrace()
            }
//        }
    }


    @Composable
    fun MainScreen() {
        val navController = rememberNavController()

        var showBottomBar by remember { mutableStateOf(false) }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        // 1px line at top of bar
                        Divider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        BottomNavigationBar(navController)
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    // apply only bottom padding so top is still edge-to-edge
                    .padding(bottom = paddingValues.calculateBottomPadding())
            ) {
                NavGraphV2(
                    navController = navController,
                    onBottomBarVisibleChange = { showBottomBar = it }
                )
            }
        }


    }
    private fun setupTransparentNavigationBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.setDecorFitsSystemWindows(false)
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            // Android 10 and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            window.navigationBarColor = Color.TRANSPARENT

            // For API 27+ you can also make the navigation bar fully transparent with navigation bar divider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
        }

        // Optional: Make status bar transparent too
        window.statusBarColor = Color.TRANSPARENT
    }

    fun getUniqueDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

//
//    fun getUniqueDeviceId2(context: Context): String {
//        return context.applicationContext.packageManager.
//    }

    suspend fun fetchMangaLoadMore(api: IMangaDataApiServices): Response<String> {
        val baseUrl = "https://manhastro.net"

        val url =  "$baseUrl/wp-admin/admin-ajax.php"
        val commonHeaders = Headers.Builder()
            .set("Cache-Control", "max-age=0")
            .build()
        val formHeaders = commonHeaders.newBuilder()
//            .set("Accept", "*/*")
//            .set("Accept-Encoding", "gzip, deflate, br")
//            .set("Accept-Language", "pt-BR,en-US;q=0.7,en;q=0.3")
            .set("Connection", "keep-alive")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
//            .set("Sec-Fetch-Site", "same-origin")
//            .set("Sec-Fetch-Mode", "cors")
//            .set("Sec-Fetch-Dest", "empty")
//            .set("Priority", "u=0")
//            .set("TE", "trailers")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        // 3) Build the form body
        val formBody = FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", (1 - 1).toString())
            .add("template", "madara-core/content/content-archive")
            .add("vars[orderby]", "meta_value_num")
            .add("vars[paged]", "1")
            .add("vars[post_type]", "wp-manga")
            .add("vars[post_status]", "publish")
            .add("vars[meta_key]", if (true) "_wp_manga_views" else "_latest_update")
            .add("vars[order]", "desc")
            .add("vars[sidebar]", "right")
            .add("vars[manga_archives_item_layout]", "big_thumbnail")
            .build()



        return api.post(
            url = url,
          body = formBody,
            headers = formHeaders.toMap()
        )
    }
    suspend fun fetchBatcavePage(
        api: IMangaDataApiServices,
        page: Int = 2
    ): Response<String> {
        val url = "https://batcave.biz/33234-marvel-rivals-infinity-comic-2024.html"

        // form body (URL-encoded)
        val formBody: RequestBody = FormBody.Builder()
            .add("dlenewssortby", "editdate")
            .add("dledirection", "desc")
            .add("set_new_sort", "dle_sort_cat_1")
            .add("set_direction_sort", "dle_direction_cat_1") // empty value as in your example
            .build()

        val headers = mapOf(
            "Accept-Encoding" to "br,gzip",
            "Cache-Control" to "max-age=600",
            "Connection" to "Keep-Alive",
//            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to "SITE_TOTAL_ID=49e4eb19b374e69df54695162fc26183; _ga=GA1.1.456038851.1759754251; PHPSESSID=a77371fb1579531046a3807dde993a15; viewed_ids=33234,33179,6966,33834; cf_clearance=6068AMaHEQYSE6iNWbhGmTkwOLvKWe9vpMZGGsAmXNs-1759781893-1.2.1.1-_xJGNe7Mvn15DQxcEvo9CuOTr2zfxl.gZE.Irap79fCqQLUoJo4rSKa4THOhy_wlcNr2biugO0tgCkd7KL0VW_H5m9WC98gLo6w.P9KmorHCucN8HtbbAgrJZKV_mrRLyLxCXij0kM5x8I0y10oUfeSWBKD.ZIJ4VxyP2qFJxzlYN6N8YOVP0GHIOx6axsXEanfDVghY6TRKYCfBqTHZP5bqPab8g.yJ_o1uNB92_bw; _ga_9QJ4GXKEG4=GS2.1.s1759779460\$o7\$g1\$t1759782357\$j60\$l0\$h0",
            "Referer" to "https://batcave.biz/",
//            "User-Agent" to "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            "Host" to "batcave.biz"
        )

        return api.get(url = url,
//            body = formBody,
            headers = headers
        )
    }
    suspend fun fetchMangaParkSearch(
        api: IMangaDataApiServices,
        searchSelectJson: String? = null // JSON string for the "select" object, or null
    ): Response<String> {
        val baseUrl = "https://mangapark.net"
        val url = "$baseUrl/apo/"

        // Headers for GraphQL request
        val graphqlHeaders = Headers.Builder()

              .set("User-Agent", "PostmanRuntime/7.45.0")
            .set("Accept", "*/*")
            .set("Accept-Encoding", "gzip, deflate, br")
            .set("Connection", "keep-alive")
            .set("Host", "${baseUrl}/")
            .set("Content-Type", "application/json")


            .build()

        // GraphQL query
        val query = """
        query (${'$'}select: SearchComic_Select) {
          get_searchComic(select: ${'$'}select) {
            items {
              data {
                id
                name
                altNames
                artists
                authors
                genres
                originalStatus
                uploadStatus
                summary
                extraInfo
                urlCoverOri
                urlPath
                max_chapterNode {
                  data {
                    imageFile {
                      urlList
                    }
                  }
                }
                first_chapterNode {
                  data {
                    imageFile {
                      urlList
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

        // Build GraphQL request body
        val variablesJson: JsonElement = if (searchSelectJson != null) {
            // parse the provided JSON into JsonElement (throws if invalid JSON)
            val selectElement = Json.parseToJsonElement(searchSelectJson)
            buildJsonObject { put("select", selectElement) }
        } else {
            // empty variables object
            buildJsonObject {}
        }
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variablesJson)
        }

        val jsonBody = payload.toString()
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val url2 = "https://inmanga.com/manga/getMangasConsultResult"

        // Convert to JSON string (you'll need a JSON library like Gson or kotlinx.serialization)
        val headersMap: Map<String, String> = graphqlHeaders.toMultimap().mapValues { it.value.joinToString(",") }


        val requestBody2 = buildInMangaRequestBody(page =1, isPopular = true)

        val headers = mapOf(
//            "X-Requested-With" to "XMLHttpRequest",
//            "Origin" to "https://inmanga.com",
//            "Referer" to "https://inmanga.com/manga/consult",
            "Accept" to "application/json",
        )

        val detales ="https://inmanga.com/ver/manga/Kimetsu-no-Yaiba/78352626-0e2c-4b10-9610-28abf57c6881"
        val mangaurl = "https://inmanga.com/chapter/getall?mangaIdentification=5b2d24eb-5de6-4fc7-a56a-fc6dd6510b7c"

        val cahapterurl ="https://pack-yak.intomanga.com/images/manga/Tokyo-Ghoul/chapter/142/page/2/8edb1fc9-8c2f-4d23-8766-a477068777f8"

        return api.post(
            url = "https://readcomiconline.li/AdvanceSearch?comicName=dear",
            body = requestBody2,
            headers = headers
        )
    }
    fun shareTextFile(context: Context, filename: String, text: String) {
        // create file in app cache (overwrites if exists)
        val file = File(context.cacheDir, filename)
        file.writeText(text) // may throw -> will be caught by caller

        // get Uri via FileProvider
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        // build chooser intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // If context is an Activity you can call startActivity directly.
        // If context is application context, add NEW_TASK.
        val launchIntent = Intent.createChooser(shareIntent, "Share file")
        if (context !is Activity) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }

    fun buildInMangaRequestBody(
        page: Int,
        isPopular: Boolean
    ): RequestBody {
        val skip = (page - 1) * 10
        val sortBy = if (isPopular) 1 else 3 // 1 = Popular, 3 = Latest

        val bodyString =
            "filter%5Bgeneres%5D%5B%5D=-1" +
                    "&filter%5BqueryString%5D=" +
//             quary =       "demon" +
                    "&filter%5Bskip%5D=$skip" +
                    "&filter%5Btake%5D=10" +
                    "&filter%5Bsortby%5D=$sortBy" +
                    "&filter%5BbroadcastStatus%5D=0" +
                    "&filter%5BonlyFavorites%5D=false" +
                    "&d="

        return bodyString.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
    }
    private fun convertToJson(obj: Map<String, Any?>): String {
        return buildJsonObject {
            obj.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    is Map<*, *> -> put(key, convertMapToJsonObject(value as Map<String, Any?>))
                    is List<*> -> put(key, convertListToJsonArray(value))
                    null -> put(key, JsonNull)
                    else -> put(key, value.toString())
                }
            }
        }.toString()
    }

    private fun convertMapToJsonObject(map: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    is Map<*, *> -> put(key, convertMapToJsonObject(value as Map<String, Any?>))
                    is List<*> -> put(key, convertListToJsonArray(value))
                    null -> put(key, JsonNull)
                    else -> put(key, value.toString())
                }
            }
        }
    }

    private fun convertListToJsonArray(list: List<*>): JsonArray {
        return buildJsonArray {
            list.forEach { value ->
                when (value) {
                    is String -> add(value)
                    is Number -> add(value)
                    is Boolean -> add(value)
                    is Map<*, *> -> add(convertMapToJsonObject(value as Map<String, Any?>))
                    is List<*> -> add(convertListToJsonArray(value))
                    null -> add(JsonNull)
                    else -> add(value.toString())
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()

        // Add null check and error handling
        if (!isFinishing && !isDestroyed) {
            try {
                AppUpdateHelper.resumeUpdate(this) {
                    Log.d("MainActivity", "Completing flexible update")
                    AppUpdateHelper.completeUpdate()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error resuming update", e)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) {
            when (resultCode) {
                RESULT_OK -> {
                    Log.d("MainActivity", "Update flow completed successfully")
                }
                RESULT_CANCELED -> {
                    Log.d("MainActivity", "Update flow canceled by user")
                    // Handle user cancellation
                }
                else -> {
                    Log.w("MainActivity", "Update flow failed with result code: $resultCode")

                    // Only finish app for immediate updates if specifically required
                    if (updateType) {
                        // Give user option instead of force closing
                        finishAffinity()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reviewScope.cancel()

        try {
            AppUpdateHelper.unregisterListener()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during cleanup", e)
        }
    }





    @Serializable
    data class SerializableComplaint(
        val id: String,
        val userId: String,
        val type: String,
        val subject: String,
        val body: String,
        val createdAt: String?,
        val status: String,
        val metadata: Map<String, String>? = null
    )

    fun Complaint.toSerializable(): SerializableComplaint {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return SerializableComplaint(
            id = id,
            userId = userId,
            type = type.name,
            subject = subject,
            body = body,
            createdAt = createdAt?.let { dateFormat.format(it) },
            status = status.name,
            metadata = metadata?.mapValues { it.value.toString() } // convert Any to String
        )
    }

    fun saveToDownloadsFile(filename: String, json: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, filename)
            file.writeText(json)
        } catch (e: Exception) {
        }
    }

}

