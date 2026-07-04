package me.manga.yamiapk.core.states

import android.content.Context
import androidx.annotation.StringRes

interface StringProvider {
    fun getString(@StringRes resId: Int, vararg formatArgs: Any): String
}

class AndroidStringProvider(private val ctx: Context) : StringProvider {
    override fun getString(resId: Int, vararg formatArgs: Any) =
        ctx.getString(resId, *formatArgs)
}