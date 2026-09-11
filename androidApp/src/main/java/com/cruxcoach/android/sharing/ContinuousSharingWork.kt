package com.cruxcoach.android.sharing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Host-testable execution policy shared by lifecycle work and the real Worker. */
internal class ContinuousSyncTask(
    private val hasWork: () -> Boolean,
    private val synchronize: suspend () -> Boolean,
) {
    suspend fun run(): Boolean {
        return try { !hasWork() || synchronize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false } // No provider messages or private fields in logs.
    }
}

@HiltWorker
class ContinuousSharingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val controller: SharingController,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ContinuousSyncTask(controller::continuousHasWork, controller::synchronizeContinuousAutomatically).run()) Result.success()
        else Result.retry()
    }
}

object ContinuousSharingWork {
    const val PERIODIC = "private-continuous-sharing-periodic-v1"
    const val CHANGE = "private-continuous-sharing-change-v1"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context) { periodic(context); wake(context) }
    fun periodic(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ContinuousSharingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun wake(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ContinuousSharingWorker>().setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
