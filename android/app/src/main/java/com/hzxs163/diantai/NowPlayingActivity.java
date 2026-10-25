package com.hzxs163.diantai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 播放详情页：大按钮、上一台/下一台、收藏、睡眠定时、ICY 曲目。 */
public class NowPlayingActivity extends Activity {

    private TextView name;
    private TextView meta;
    private TextView nowTitle;
    private TextView sleepState;
    private ImageButton play;
    private ImageButton star;

    private SharedPreferences appPrefs;
    private Set<String> favs = new HashSet<>();
    private BroadcastReceiver receiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now);

        appPrefs = getSharedPreferences("app", Context.MODE_PRIVATE);
        favs = new HashSet<>(appPrefs.getStringSet("favs", Collections.<String>emptySet()));

        name = findViewById(R.id.name);
        meta = findViewById(R.id.meta);
        nowTitle = findViewById(R.id.now_title);
        sleepState = findViewById(R.id.sleep_state);
        play = findViewById(R.id.play);
        star = findViewById(R.id.star);

        findViewById(R.id.close).setOnClickListener(v -> finish());
        play.setOnClickListener(v -> send(PlayerService.ACTION_TOGGLE));
        findViewById(R.id.prev).setOnClickListener(v -> step(-1));
        findViewById(R.id.next).setOnClickListener(v -> step(1));
        star.setOnClickListener(v -> toggleFav());
        findViewById(R.id.sleep).setOnClickListener(v -> pickSleep());

        play.setColorFilter(getResources().getColor(R.color.white, getTheme()));

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                render();
            }
        };
        IntentFilter f = new IntentFilter(PlayerService.BC_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        ticker = new Runnable() {
            @Override
            public void run() {
                renderSleep();
                ui.postDelayed(this, 20000);
            }
        };
        ui.postDelayed(ticker, 20000);
    }

    @Override
    protected void onPause() {
        if (ticker != null) ui.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void render() {
        String url = PlayerService.lastUrl;
        Station s = UiBridge.byUrl.get(url);
        String title = s != null ? s.displayName() : PlayerService.lastName;
        String sub = s != null ? s.meta() : PlayerService.lastSub;
        name.setText(title.isEmpty() ? "电台" : title);
        meta.setText(sub);
        String icy = PlayerService.lastTitle;
        nowTitle.setText(icy);
        nowTitle.setVisibility(icy == null || icy.isEmpty() ? View.GONE : View.VISIBLE);

        boolean playing = PlayerService.S_PLAYING.equals(PlayerService.lastState)
                || PlayerService.S_CONNECTING.equals(PlayerService.lastState);
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setColorFilter(getResources().getColor(R.color.white, getTheme()));

        boolean fav = favs.contains(url);
        star.setImageResource(fav ? R.drawable.ic_star_on : R.drawable.ic_star_off);
        renderSleep();
    }

    private void renderSleep() {
        long left = PlayerService.lastSleepDeadline - SystemClock.elapsedRealtime();
        if (PlayerService.lastSleepDeadline <= 0 || left <= 0) {
            sleepState.setVisibility(View.GONE);
            return;
        }
        long min = Math.max(1, (left + 59999) / 60000);
        sleepState.setText("睡眠定时：还剩 " + min + " 分钟");
        sleepState.setVisibility(View.VISIBLE);
    }

    private void step(int delta) {
        List<String> queue = UiBridge.queue;
        if (queue == null || queue.isEmpty()) return;
        int i = queue.indexOf(PlayerService.lastUrl);
        if (i < 0) i = 0;
        int n = ((i + delta) % queue.size() + queue.size()) % queue.size();
        Station s = UiBridge.byUrl.get(queue.get(n));
        if (s == null) return;
        Intent intent = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY)
                .putExtra(PlayerService.EXTRA_URL, s.url)
                .putExtra(PlayerService.EXTRA_NAME, s.displayName())
                .putExtra(PlayerService.EXTRA_SUB, s.meta());
        startService(intent);
        Recent.add(this, s.url);
        render();
    }

    private void send(String action) {
        startService(new Intent(this, PlayerService.class).setAction(action));
    }

    private void toggleFav() {
        String url = PlayerService.lastUrl;
        if (url.isEmpty()) return;
        if (favs.contains(url)) favs.remove(url);
        else favs.add(url);
        appPrefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        render();
    }

    private void pickSleep() {
        final String[] labels = {"关闭", "15 分钟", "30 分钟", "60 分钟"};
        final int[] minutes = {0, 15, 30, 60};
        new AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setItems(labels, (d, which) -> {
                    Intent i = new Intent(this, PlayerService.class)
                            .setAction(PlayerService.ACTION_SLEEP)
                            .putExtra(PlayerService.EXTRA_MINUTES, minutes[which]);
                    startService(i);
                    renderSleep();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
