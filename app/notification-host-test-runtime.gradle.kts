import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.security.MessageDigest

// Like data:download's host inputs, these archives are data only, never Java classpath entries.
val notificationHostCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val notificationHostNativeInput by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val notificationHostSdkInput by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(notificationHostNativeInput.name, notificationHostCatalog.findLibrary("sqlite-host-jni-input").get())
    add(notificationHostSdkInput.name, notificationHostCatalog.findLibrary("robolectric-sdk35").get())
}

val notificationHostRuntime = layout.buildDirectory.dir("notification-android-host-runtime")
val prepareNotificationAndroidHostRuntime by tasks.registering(Sync::class) {
    from({ zipTree(notificationHostNativeInput.singleFile) }) {
        include("natives/linux_x64/libsqliteJni.so")
        eachFile { path = "native/libsqliteJni.so" }
        includeEmptyDirs = false
    }
    from(notificationHostSdkInput) { into("sdk") }
    into(notificationHostRuntime)
}

tasks
    .withType<Test>()
    .matching { it.name == "testDebugUnitTest" }
    .configureEach {
        dependsOn(prepareNotificationAndroidHostRuntime)
        maxParallelForks = 1
        forkEvery = 0
        maxHeapSize = "1g"
        jvmArgs("-XX:ActiveProcessorCount=2")
        // Set before the fork: Gradle offline mode does not control Robolectric's own resolver.
        systemProperty("robolectric.offline", "true")
        systemProperty(
            "robolectric.dependency.dir",
            notificationHostRuntime.get().dir("sdk").asFile.absolutePath,
        )
        systemProperty(
            "kira.notification.sqlite.native",
            notificationHostRuntime.get().file("native/libsqliteJni.so").asFile.absolutePath,
        )
        doFirst {
            val forbidden =
                classpath.files.filter {
                    it.name.startsWith("sqlite-bundled-jvm-") || it.name.startsWith("room-runtime-jvm-")
                }
            check(forbidden.isEmpty()) {
                "Desktop SQLite/Room classes must not enter the Android host classpath: $forbidden"
            }
            val sdk =
                notificationHostRuntime
                    .get()
                    .file("sdk/android-all-instrumented-15-robolectric-13954326-i7.jar")
                    .asFile
            check(sdk.isFile) { "Pinned offline Robolectric SDK35 input was not staged" }
            check(sdk.notificationHostSha256() == "06c4c8602d6db7486266a982bc55834ed6dfebd62a0b88daca204139ecf142cb") {
                "Offline Robolectric SDK35 input does not match the pinned host runtime"
            }
        }
    }

fun File.notificationHostSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
