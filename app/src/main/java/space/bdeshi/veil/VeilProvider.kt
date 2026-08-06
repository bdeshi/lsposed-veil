package space.bdeshi.veil

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Exists only so hooked app processes can report "LSPosed loaded me into package X" back to
 * Veil's own settings screen — cross-process writes to another app's SharedPreferences aren't
 * possible any other way. No persistent service; the OS starts this app briefly on demand for
 * each call() and it's free to be killed again right after.
 */
class VeilProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "register" && !arg.isNullOrBlank()) {
            registerDiscovered(arg)
        }
        return null
    }

    private fun registerDiscovered(packageName: String) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(Features.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(Features.DISCOVERED_PACKAGES_KEY, emptySet()) ?: emptySet()
        if (packageName !in current) {
            prefs.edit().putStringSet(Features.DISCOVERED_PACKAGES_KEY, current + packageName).apply()
            fixPermissions(ctx)
        }
    }

    private fun fixPermissions(ctx: Context) {
        try {
            val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            prefsDir.listFiles()?.forEach { it.setReadable(true, false) }
        } catch (t: Throwable) {
            // Best-effort.
        }
    }

    // No actual table behind this provider — it exists purely for call().
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
