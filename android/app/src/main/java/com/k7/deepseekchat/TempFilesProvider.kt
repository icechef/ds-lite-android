package com.k7.deepseekchat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 最小版 FileProvider：只用来给「拍照」一个可写的 content:// 目标。
 *
 * 为什么不用 MediaStore 插入条目：实测（vivo V2430A / Android 16）相机 App 拿到
 * `IS_PENDING=1` 的条目写不进去，点「确定」直接回 RESULT_CANCELED、文件为空
 * （logcat: camera result=0 uri=content://media/... exists=false）。
 * 为什么不用 androidx.core 的 FileProvider：本项目零第三方依赖，且这里只需要 openFile + 元数据查询。
 */
class TempFilesProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.k7.deepseekchat.temp"

        fun dir(ctx: Context): File = File(ctx.cacheDir, "capture").apply { mkdirs() }

        fun uriFor(file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(file.name)
            .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    private fun fileOf(uri: Uri): File {
        val name = uri.lastPathSegment ?: ""
        require(name.isNotEmpty() && !name.contains('/') && !name.contains("..")) { "bad path" }
        return File(dir(context!!), name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.parseMode(mode))

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val values = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name as Any?
                OpenableColumns.SIZE -> file.length() as Any?
                else -> null
            }
        }
        cursor.addRow(values.toTypedArray())
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val file = runCatching { fileOf(uri) }.getOrNull() ?: return 0
        return if (file.exists() && file.delete()) 1 else 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
