package coredevices.coreapp.util

import CoreAppVersion
import PlatformUiContext
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.requestUpdateFlow
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import coredevices.util.R
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

actual data class AppUpdatePlatformContent(
    val androidUpdate: AppUpdateInfo? = null,
    val githubRelease: GithubApkRelease? = null,
)

class AndroidAppUpdate(
    private val appUpdateManager: AppUpdateManager,
    private val settings: Settings,
    private val context: Context,
    private val githubAppUpdateChecker: GithubAppUpdateChecker,
    private val githubApkDownloadManager: GithubApkDownloadManager,
    private val appVersion: CoreAppVersion,
) : AppUpdate {
    private val logger = Logger.withTag("AndroidAppUpdate")

    override val updateAvailable: StateFlow<AppUpdateState> = updateFlow()
        .catch { exception ->
            logger.w(exception) { "Failed to check for Pebble App updates" }
            emit(AppUpdateState.NoUpdateAvailable)
        }
        .onEach { result ->
            if (result is AppUpdateState.UpdateAvailable) {
                maybeCreateUpdateNotification(result.update)
            }
        }
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 30.minutes.inWholeMilliseconds,
                replayExpirationMillis = 12.hours.inWholeMilliseconds,
            ),
            initialValue = AppUpdateState.NoUpdateAvailable,
        )

    override fun startUpdateFlow(uiContext: PlatformUiContext, update: AppUpdatePlatformContent) {
        update.androidUpdate?.let { androidUpdate ->
            if (androidUpdate.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                logger.d { "Starting Play update flow" }
                appUpdateManager.startUpdateFlowForResult(
                    androidUpdate,
                    AppUpdateType.IMMEDIATE,
                    uiContext.activity,
                    REQUEST_CODE_APP_UPDATE,
                )
            } else {
                logger.d { "Play update type not allowed" }
            }
            return
        }

        update.githubRelease?.let { release ->
            logger.d { "Starting GitHub APK update download" }
            githubApkDownloadManager.enqueue(release)
        }
    }

    private fun updateFlow() = flow {
        val installer = resolveInstaller()
        logger.d { "Checking for app update, installed via $installer" }
        if (installer == PLAY_STORE_PACKAGE) {
            emitAll(
                appUpdateManager.requestUpdateFlow().map { result ->
                    when (result) {
                        is AppUpdateResult.Available -> AppUpdateState.UpdateAvailable(
                            AppUpdatePlatformContent(androidUpdate = result.updateInfo),
                        )

                        else -> AppUpdateState.NoUpdateAvailable
                    }
                }
            )
        } else {
            val githubRelease = githubAppUpdateChecker.checkForUpdate(appVersion.version)
            emit(
                githubRelease?.let { AppUpdateState.UpdateAvailable(AppUpdatePlatformContent(githubRelease = it)) }
                    ?: AppUpdateState.NoUpdateAvailable
            )
        }
    }

    private fun resolveInstaller(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (exception: Exception) {
        logger.w(exception) { "Failed to resolve install source" }
        null
    }

    private fun maybeCreateUpdateNotification(update: AppUpdatePlatformContent) {
        val lastPromptedMs = settings.getLong(LAST_PROMPTED_KEY, 0L)
        val nowMs = System.currentTimeMillis()
        val diff = (nowMs - lastPromptedMs).milliseconds
        if (diff > NOTIFICATION_ALLOWED_PERIOD) {
            settings.set(LAST_PROMPTED_KEY, nowMs)
            createUpdateNotification(update)
        } else {
            logger.d { "Not notifying for update, only $diff since last prompt" }
        }
    }

    private fun createUpdateNotification(update: AppUpdatePlatformContent) {
        val intent = when {
            update.androidUpdate != null -> getPlayStoreMarketIntent(context, context.packageName)
            update.githubRelease != null -> context.packageManager.getLaunchIntentForPackage(context.packageName)
            else -> null
        } ?: run {
            logger.w { "Failed to create app update notification intent" }
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        context.createUpdateChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pebble App Update Available")
            .setContentText("Please update the Pebble app")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun getPlayStoreMarketIntent(context: Context, packageName: String): Intent? {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            setPackage(PLAY_STORE_PACKAGE)
        }
        return marketIntent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun Context.createUpdateChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Pebble App updates"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val REQUEST_CODE_APP_UPDATE = 12346
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val LAST_PROMPTED_KEY = "last_prompted_app_update"
        private val NOTIFICATION_ALLOWED_PERIOD = 1.days
        private const val CHANNEL_ID = "app_update_channel"
        private const val NOTIFICATION_ID = 3006089
    }
}
