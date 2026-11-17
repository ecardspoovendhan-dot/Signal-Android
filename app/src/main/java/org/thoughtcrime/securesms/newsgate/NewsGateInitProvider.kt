package org.thoughtcrime.securesms.newsgate

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts [NewsGate] when Signal's process starts, without touching any of
 * Signal's own code. Android creates content providers automatically before
 * the app runs, so this is a self-contained hook. It serves no data.
 */
class NewsGateInitProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    (context?.applicationContext as? Application)?.let { NewsGate.init(it) }
    return true
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
