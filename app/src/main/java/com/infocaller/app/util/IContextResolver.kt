package com.infocaller.app.util

import android.content.ContentResolver
import android.net.Uri

/**
 * Interface to abstract Android Context for better testability.
 */
interface IContextResolver {
    fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor?
}

class AndroidContextResolver(private val context: android.content.Context) : IContextResolver {
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? {
        return context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}
