package me.manga.yamiapk.dex

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

object DexPluginLoader {

    private fun loadDexFromAssets(context: Context, assetName: String): File {
        val dexDir = File(context.codeCacheDir, "dexPlugins")
        dexDir.mkdirs()

        val outFile = File(dexDir, assetName)
        context.assets.open(assetName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    fun loadPlugin(context: Context, dexName: String): Any {
        val dexFile = loadDexFromAssets(context, dexName)

        val optimizedDir = File(context.codeCacheDir, "dexOpt").apply { mkdirs() }

        val classLoader = DexClassLoader(
            dexFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            context.classLoader
        )

        // Same package & object name in your PluginData.kt
        val pluginClass = classLoader.loadClass("me.manga.yamiapk.dex.AasqPlugin")

        return pluginClass.getDeclaredField("INSTANCE").get(null)
    }

    fun runPluginJson(context: Context, json: String): Any {
        val plugin = loadPlugin(context, "PluginData.dex")

        val method = plugin::class.java.getMethod("parseFromJson", String::class.java)

        return method.invoke(plugin, json)
    }
}
