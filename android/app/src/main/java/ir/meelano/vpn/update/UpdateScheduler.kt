package ir.meelano.vpn.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.meelano.vpn.MeelanoApp
import ir.meelano.vpn.data.AppSettings
import java.util.concurrent.TimeUnit

/**
 * «بررسی خودکار نسخه» — the periodic half of self-update. A phone that stays on for weeks must
 * still learn about a new version without the user opening anything: one tiny version GET per day,
 * on any network (the *download* is the gated half — UpdateManager starts it only on unmetered
 * networks when «دانلود خودکار» is on).
 *
 * Same idiom as data/FeedScheduler: WorkManager batches the wake-up on every skin, the CONNECTED
 * constraint means an offline phone does not even start, and UPDATE policy makes a changed setting
 * take effect immediately.
 */
object UpdateScheduler {

    private const val WORK_TAG = "meelano_update_check"

    fun schedule(context: Context) {
        if (!AppSettings.autoCheckUpdate) {
            cancel(context)
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppSettings.autoCheckUpdate) return Result.success()
        val app = applicationContext as? MeelanoApp ?: return Result.success()
        runCatching { app.updates.checkNow() }
        return Result.success()
    }
}
