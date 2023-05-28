package com.sanmer.mrepo.app.utils

import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.sanmer.mrepo.App
import com.sanmer.mrepo.utils.expansion.toFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object MediaStoreUtils {
    private val context by lazy { App.context }
    private val cr by lazy { context.contentResolver }

    val Uri.displayName: String get() {
        if (scheme == "file") {
            return toFile().name
        }
        require(scheme == "content") { "Uri lacks 'content' scheme: $this" }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        cr.query(this, projection, null, null, null)?.use { cursor ->
            val displayNameColumn = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                return cursor.getString(displayNameColumn)
            }
        }
        return this.toString()
    }

    fun Uri.copyTo(new: File) {
        cr.openInputStream(this)?.use { input ->
            cr.openOutputStream(new.toUri())?.use { output ->
                input.copyTo(output)
            }
        }
    }

    fun File.newInputStream(): InputStream? = cr.openInputStream(toUri())

    fun File.newOutputStream(): OutputStream? = cr.openOutputStream(toUri())

    val Uri.absolutePath: String? get() = run {
        val df = DocumentFile.fromTreeUri(context, this) ?: return null

        val path = cr.openFileDescriptor(df.uri, "r")?.use {
            Os.readlink("/proc/self/fd/${it.fd}")
        }?: return null

        return path
    }

    val Uri.absoluteFile get() = absolutePath?.toFile()
}