package com.hzxs163.diantai;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 无感更新：先拉 837 B 的 meta_web.json 比 sha256，真的变了才下 2.1 MB 的整包，
 * 校验后原子替换缓存。全程不打扰用户，失败就保持原样，下次再试。
 */
public class UpdateJob extends JobService {

    private static final String TAG = "diantai/update";
    private static final int JOB_ID = 2001;

    private static final long OK_INTERVAL = 6L * 3600 * 1000;    // 成功后 6 小时内不再查
    private static final long FAIL_INTERVAL = 30L * 60 * 1000;   // 失败 30 分钟后再试

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == JOB_ID) return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24L * 3600 * 1000)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Exception e) {
            Log.w(TAG, "schedule failed", e);
        }
    }

    /** 开屏时调一次；success/fail 分别按 6h / 30min 节流，force 忽略节流。 */
    static void checkNow(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(ctx);
        long ok = p.getLong("lastOk", 0);
        long fail = p.getLong("lastFail", 0);
        if (!force) {
            if (now - ok < OK_INTERVAL) return;
            if (now - fail < FAIL_INTERVAL) return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                boolean changed = run(app);
                SharedPreferences.Editor e = prefs(app).edit().putLong("lastOk", System.currentTimeMillis());
                if (changed) e.putLong("lastChange", System.currentTimeMillis());
                e.apply();
                if (changed) Log.i(TAG, "snapshot updated");
            } catch (Exception ex) {
                Log.w(TAG, "update failed: " + ex.getMessage());
                prefs(app).edit().putLong("lastFail", System.currentTimeMillis()).apply();
            }
        }, "snapshot-update").start();
    }

    /** @return true 表示缓存被换成了新版本 */
    private static boolean run(Context ctx) throws Exception {
        byte[] metaBytes = Net.get(Snapshot.BASE + "meta_web.json", 15000);
        JSONObject m = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
        String sha = m.optString("sha256_gz", "");
        int rows = m.optInt("web_rows", 0);
        if (rows <= 0) throw new IllegalStateException("meta 里 web_rows 无效");

        Snapshot.Meta local = Snapshot.readLocalMeta(ctx);
        if (local != null && !sha.isEmpty() && sha.equals(local.sha256) && local.rows == rows) {
            return false; // 已经是最新
        }

        byte[] gz = Net.get(Snapshot.BASE + "stations.web.json.gz", 90000);
        if (!sha.isEmpty() && !sha.equals(Snapshot.sha256(gz))) {
            throw new IllegalStateException("sha256 对不上，丢弃这次下载");
        }
        int n = Snapshot.install(ctx, gz, new Snapshot.Meta(sha, m.optString("generated_at", ""), rows));
        if (n <= 0) throw new IllegalStateException("解析出 0 条，丢弃");
        return true;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (run(app)) prefs(app).edit().putLong("lastChange", System.currentTimeMillis()).apply();
                prefs(app).edit().putLong("lastOk", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(TAG, "job update failed: " + e.getMessage());
                prefs(app).edit().putLong("lastFail", System.currentTimeMillis()).apply();
            }
            jobFinished(params, false);
        }, "snapshot-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
