package com.infocaller.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Safe Activity lookup that never crashes in previews, dialogs, or wrapped contexts. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
