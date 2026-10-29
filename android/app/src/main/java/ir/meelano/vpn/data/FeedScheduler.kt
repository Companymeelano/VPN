package ir.meelano.vpn.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * «دریافت خودکار» — the periodic half of feed freshness, so a phone that stays on for days never
 * serves a day-old free list and never waits for the user to open the app before the next best
 * server is known.
 *
 * WorkManager, not AlarmManager: the platform batches these wake-ups on every skin (Xiaomi/Huawei
 * included) and the constraint `NetworkType.CONNECTED` means a phone without a network does not
 * even wake the process. The 15-minute WorkManager floor is why the picker starts at 1 hour.
 *
 * The worker only *triggers* a refresh; the repository owns coalescing (its per-kind Mutex means
 * overlapping foreground + periodic refreshes collapse into one source download).
 */
object FeedScheduler {

    private const val WORK_TAG = "meelano_feed_sync"

    /** Every value the settings picker offers, in hours. */
    val INTERVALS = listOf(1, 3, 6, 12, 24)

    fun schedule(context: Context) {
        val hours = AppSettings.feedIntervalHours
        if (!AppSettings.autoFetch) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
            return
        }
        val req = PeriodicWorkRequestBuilder<FeedSyncWorker>(hours.coerceIn(1, 24).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE (not KEEP): changing the interval in settings must take effect now.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
    }
}

/**
 * One background refresh of both kinds. `FeedHolder.init` is idempotent and the repository is
 * process-wide, so a worker and the UI share one download — the cold-start rule from MeelanoApp
 * ("one coalesced refresh, never two") holds in the background too.
 */
class FeedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppSettings.autoFetch) return Result.success()
        val ctx = applicationContext
        FeedHolder.init(ctx)
        val repo = FeedHolder.repo
        // Refresh locks coalesce; sequential here only to keep one network story at a time on a
        // metered link (VIP first: it is the small, user-owned list).
        repo.refresh(ServerFeedRepository.KIND_VIP)
        repo.refresh(ServerFeedRepository.KIND_FREE)
        return Result.success()
    }
}
