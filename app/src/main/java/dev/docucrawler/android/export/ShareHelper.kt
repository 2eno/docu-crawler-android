package dev.docucrawler.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
    /** Exporte landen in `cacheDir/exports/`, freigegeben über den FileProvider. */
    fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Öffnet das native Android-Share-Sheet (`ACTION_SEND`) – z. B. direkt an die Claude-App. */
    fun share(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "${file.name} teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
