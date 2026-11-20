package com.sahl.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup

/**
 * Sahl Union — lets other Sahl apps (same signing key only) ask Sahl Contacts:
 *   content://com.sahl.contacts.union/lookup/<number>  -> name, lookup, photo, starred
 *   content://com.sahl.contacts.union/favorites          -> id, name, number, photo
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val seg = uri.pathSegments
        return try {
            when (seg.firstOrNull()) {
                "lookup" -> {
                    val out = MatrixCursor(arrayOf("name", "lookup", "photo", "starred"))
                    val number = seg.getOrNull(1) ?: return out
                    c.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                        arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.LOOKUP_KEY, PhoneLookup.PHOTO_THUMBNAIL_URI, PhoneLookup.STARRED),
                        null, null, null)?.use {
                        if (it.moveToFirst()) out.addRow(arrayOf(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)))
                    }
                    out
                }
                "favorites" -> {
                    val out = MatrixCursor(arrayOf("id", "name", "number", "photo"))
                    val hidden = Vault.hidden(c)
                    for (p in Repo.list(c)) if (p.starred && p.lookup !in hidden) out.addRow(arrayOf(p.id, p.name, p.numbers.firstOrNull(), p.photo))
                    out
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
