# ProGuard / R8 keep rules for the packaged (release) Desktop distribution.
#
# Wired from desktopApp/build.gradle.kts via:
#   compose.desktop { application { buildTypes.release.proguard {
#       configurationFiles.from(project.file("compose-desktop.pro")) } } }
#
# Only the release (shrunk) build uses these. The dev `./gradlew run` task does no shrinking, so a
# missing keep here would silently work in dev and only break in the packaged Msi/Dmg/Deb.
#
# Source: KCEF COMPOSE.md ("ProGuard" section).

# KCEF wraps JetBrains JCEF; the entire org.cef.* surface (CefApp, CefClient, CefBrowser, the
# native callback handlers, CefSettings field reflection, etc.) is reached from native code and via
# reflection, none of which the shrinker can trace statically. Stripping any of it crashes the
# WebView at runtime in packaged builds.
-keep class org.cef.** { *; }

# kotlinx-coroutines-swing publishes its main-dispatcher factory through a ServiceLoader
# (META-INF/services). KCEF and the WebView host dispatch onto the Swing/EDT thread through it;
# if R8 prunes the factory class the Dispatchers.Swing lookup throws and KCEF init fails. Keep the
# members too (`{ *; }`) so the no-arg <init>() the ServiceLoader invokes reflectively survives
# shrinking — a bare class-name keep does not retain it.
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
