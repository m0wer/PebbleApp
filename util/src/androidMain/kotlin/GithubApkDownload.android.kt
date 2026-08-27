package coredevices.coreapp.util

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import coredevices.util.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

class GithubApkDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val store = GithubApkDownloadStore(appContext)
    private val downloadAccess = GithubApkDownloadAccess(appContext)

    fun enqueue(release: GithubApkRelease) {
        val storedDownload = store.load()
        if (storedDownload != null && !storedDownload.matches(release)) {
            downloadAccess.remove(storedDownload.downloadId)
            store.clear()
        }

        val existingDownload = store.load()
        when (existingDownload?.let(::downloadStatus)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                logger.d { "APK update download already in progress" }
                return
            }

            DownloadManager.STATUS_SUCCESSFUL -> {
                GithubApkDownloadCompletionHandler(appContext).handle(existingDownload.downloadId)
                return
            }

            else -> store.clear()
        }

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Pebble App ${release.tag}")
            .setDescription("Downloading app update")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (exception: RuntimeException) {
            logger.w(exception) { "Failed to enqueue APK update download" }
            return
        }

        store.save(
            GithubApkDownload(
                downloadId = downloadId,
                packageName = appContext.packageName,
                releaseTag = release.tag,
                downloadUrl = release.downloadUrl,
                sha256 = release.sha256,
                verified = false,
            )
        )
        GithubApkDownloadCompletionHandler(appContext).handle(downloadId)
    }

    private fun downloadStatus(download: GithubApkDownload): Int? = downloadAccess.status(download)
}

class GithubApkDownloadCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return

        val pendingResult = goAsync()
        GithubApkDownloadCompletionHandler(context.applicationContext).handle(downloadId) {
            pendingResult.finish()
        }
    }
}

class GithubApkInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installOrRequestPermission()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_INSTALL_PERMISSION) return
        if (packageManager.canRequestPackageInstalls()) {
            installOrRequestPermission()
        } else {
            finish()
        }
    }

    private fun installOrRequestPermission() {
        val context = applicationContext
        val store = GithubApkDownloadStore(context)
        val download = store.load()?.takeIf { it.verified } ?: run {
            finish()
            return
        }
        val uri = GithubApkDownloadAccess(context).completedUri(download) ?: run {
            store.clear()
            finish()
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            startActivityForResult(permissionIntent, REQUEST_INSTALL_PERMISSION)
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        installIntent.clipData = ClipData.newRawUri("apk", uri)
        try {
            startActivity(installIntent)
        } catch (exception: RuntimeException) {
            logger.w(exception) { "Failed to start APK installer" }
        }
        finish()
    }

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1
    }
}

private class GithubApkDownloadCompletionHandler(context: Context) {
    private val appContext = context.applicationContext
    private val store = GithubApkDownloadStore(appContext)
    private val downloadAccess = GithubApkDownloadAccess(appContext)

    fun handle(downloadId: Long, onFinished: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                verifyAndNotify(downloadId)
            } catch (exception: Exception) {
                logger.w(exception) { "Failed to process completed APK update download" }
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private fun verifyAndNotify(downloadId: Long) {
        val download = store.load()?.takeIf { it.downloadId == downloadId } ?: return
        when (downloadAccess.status(download)) {
            DownloadManager.STATUS_SUCCESSFUL -> Unit
            DownloadManager.STATUS_FAILED -> {
                store.clear()
                return
            }

            else -> return
        }

        val uri = downloadAccess.completedUri(download) ?: run {
            store.clear()
            return
        }
        if (download.sha256 != null && !matchesSha256(uri, download.sha256)) {
            logger.w { "APK update checksum did not match" }
            downloadAccess.remove(download.downloadId)
            store.clear()
            return
        }

        store.markVerified(download.downloadId)
        createInstallNotification(appContext, download.releaseTag)
    }

    private fun matchesSha256(uri: Uri, expectedSha256: String): Boolean {
        val expectedBytes = expectedSha256.hexToBytes() ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        val input = appContext.contentResolver.openInputStream(uri) ?: return false
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead < 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return MessageDigest.isEqual(digest.digest(), expectedBytes)
    }
}

private class GithubApkDownloadAccess(context: Context) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    fun status(download: GithubApkDownload): Int? = downloadManager.query(download.downloadId) { cursor ->
        val sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
        if (sourceUrl == download.downloadUrl) {
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } else {
            null
        }
    }

    fun completedUri(download: GithubApkDownload): Uri? {
        if (status(download) != DownloadManager.STATUS_SUCCESSFUL) return null
        return downloadManager.getUriForDownloadedFile(download.downloadId)
    }

    fun remove(downloadId: Long) {
        downloadManager.remove(downloadId)
    }
}

private class GithubApkDownloadStore(context: Context) {
    private val packageName = context.packageName
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(download: GithubApkDownload) {
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, download.downloadId)
            .putString(KEY_PACKAGE_NAME, download.packageName)
            .putString(KEY_RELEASE_TAG, download.releaseTag)
            .putString(KEY_DOWNLOAD_URL, download.downloadUrl)
            .putString(KEY_SHA256, download.sha256)
            .putBoolean(KEY_VERIFIED, download.verified)
            .apply()
    }

    fun load(): GithubApkDownload? {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val storedPackageName = preferences.getString(KEY_PACKAGE_NAME, null)
        val releaseTag = preferences.getString(KEY_RELEASE_TAG, null)
        val downloadUrl = preferences.getString(KEY_DOWNLOAD_URL, null)
        if (downloadId <= 0L || storedPackageName != packageName || releaseTag == null || downloadUrl == null) return null

        return GithubApkDownload(
            downloadId = downloadId,
            packageName = storedPackageName,
            releaseTag = releaseTag,
            downloadUrl = downloadUrl,
            sha256 = preferences.getString(KEY_SHA256, null),
            verified = preferences.getBoolean(KEY_VERIFIED, false),
        )
    }

    fun markVerified(downloadId: Long) {
        if (load()?.downloadId == downloadId) {
            preferences.edit().putBoolean(KEY_VERIFIED, true).apply()
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "github_app_update_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_PACKAGE_NAME = "package_name"
        private const val KEY_RELEASE_TAG = "release_tag"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_VERIFIED = "verified"
    }
}

private data class GithubApkDownload(
    val downloadId: Long,
    val packageName: String,
    val releaseTag: String,
    val downloadUrl: String,
    val sha256: String?,
    val verified: Boolean,
) {
    fun matches(release: GithubApkRelease): Boolean =
        releaseTag == release.tag && downloadUrl == release.downloadUrl && sha256 == release.sha256
}

private inline fun <T> DownloadManager.query(downloadId: Long, block: (android.database.Cursor) -> T): T? {
    val cursor = query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
    return cursor.use {
        if (it.moveToFirst() && it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) != null) {
            block(it)
        } else {
            null
        }
    }
}

private fun String.hexToBytes(): ByteArray? {
    if (length != SHA256_HEX_LENGTH || any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun createInstallNotification(context: Context, releaseTag: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
    val intent = Intent(context, GithubApkInstallActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        INSTALL_NOTIFICATION_ID,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Pebble App update ready")
        .setContentText("Version $releaseTag is ready to install")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(INSTALL_NOTIFICATION_ID, notification)
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val SHA256_HEX_LENGTH = 64
private const val INSTALL_NOTIFICATION_CHANNEL_ID = "github_app_update_channel"
private const val INSTALL_NOTIFICATION_ID = 3006090
private val logger = Logger.withTag("GithubApkUpdate")
