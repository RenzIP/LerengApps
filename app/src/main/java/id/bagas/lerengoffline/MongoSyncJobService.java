package id.bagas.lerengoffline;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Menjalankan sinkron otomatis di luar thread utama, termasuk saat aplikasi tertutup. */
public final class MongoSyncJobService extends JobService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override public boolean onStartJob(JobParameters params) {
        EXECUTOR.execute(() -> {
            boolean ok = MongoSync.syncAll(getApplicationContext());
            jobFinished(params, !ok);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // Sistem menghentikan job (mis. jaringan hilang); minta dijadwalkan ulang.
        return true;
    }
}
