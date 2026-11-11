package id.bagas.lerengoffline;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * Penjadwal sinkron otomatis. Tidak butuh WorkManager/AndroidX: JobScheduler
 * sudah tersedia sejak Android 5 dan minSdk aplikasi ini 26.
 */
final class MongoSyncScheduler {
    static final int JOB_PERIODIC = 4101;
    static final int JOB_NOW = 4102;
    static final long PERIOD_MS = 15L * 60L * 1000L;

    private MongoSyncScheduler() {}

    static boolean isReady(Context context) {
        return MongoSync.isAutoEnabled(context) && MongoSync.isConfigured(context);
    }

    /** Dipanggil saat pengaturan berubah, aplikasi dibuka, atau boot selesai. */
    static void configure(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!isReady(context)) {
            scheduler.cancel(JOB_PERIODIC);
            scheduler.cancel(JOB_NOW);
            return;
        }
        JobInfo periodic = new JobInfo.Builder(JOB_PERIODIC,
                new ComponentName(context, MongoSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        scheduler.schedule(periodic);
        requestSync(context, 4000);
    }

    /**
     * Satu permintaan dengan ID tetap, jadi permintaan beruntun otomatis
     * digabung (debounce) tanpa menumpuk pekerjaan.
     */
    static void requestSync(Context context, long delayMs) {
        if (!isReady(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        long delay = Math.max(0, delayMs);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_NOW,
                new ComponentName(context, MongoSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 60_000L);
        // Backoff bawaan JobInfo sudah eksponensial 30 detik; tidak perlu diatur.
        scheduler.schedule(builder.build());
    }

    static void cancelAll(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.cancel(JOB_PERIODIC);
        scheduler.cancel(JOB_NOW);
    }
}
