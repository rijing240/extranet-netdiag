package dev.extranet.netdiag.app

import android.content.Context
import java.io.File

/** Where a report ended up on disk. */
public data class WrittenReport(
    /** The internal copy, which is the one that can always be retrieved from a device. */
    public val path: String,
    /** The external copy, when external storage was available to write to. */
    public val externalPath: String?,
)

/**
 * Writes a report twice: to internal storage, and to the app's external files directory.
 *
 * Two copies for two audiences, and neither is optional by accident:
 *
 *  * The internal copy is the one that can always be read back. Since Android 11 the shell user
 *    cannot read `/sdcard/Android/data`, so `adb pull` of the external copy fails on any modern
 *    device and the report has to come out through `adb exec-out run-as <applicationId> cat`.
 *  * The external copy is the one a human can find with a file manager when the app is installed
 *    on their own phone.
 *
 * The external write is best-effort: an unmounted store is not a measurement failure. Both B0 and
 * B1 need exactly this, which is why it lives here rather than in either harness.
 */
public object ReportFiles {

    /** Writes [json] under [fileName], returning both paths. */
    public fun write(
        context: Context,
        fileName: String,
        json: String,
        outputDirectory: File? = null,
    ): WrittenReport {
        val appContext = context.applicationContext

        val internalFile = File(appContext.filesDir, fileName)
        internalFile.writeText(json)

        val directory = outputDirectory ?: appContext.getExternalFilesDir(null)
        val externalFile = directory?.let { File(it, fileName) }?.also { file ->
            file.parentFile?.mkdirs()
            file.writeText(json)
        }

        return WrittenReport(path = internalFile.absolutePath, externalPath = externalFile?.absolutePath)
    }
}
