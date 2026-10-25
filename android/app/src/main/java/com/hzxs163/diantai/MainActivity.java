package com.hzxs163.diantai;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int TAB_ZH = 0;
    private static final int TAB_ALL = 1;
    private static final int TAB_FAV = 2;

    private final List<Station> all = new ArrayList<>();
    private final List<Station> visible = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private StationAdapter adapter;
    private ListView list;
    private ProgressBar progress;
    private TextView empty;
    private TextView snapshotLabel;
    private TextView[] tabs;
    private EditText search;
    private View bar;
    private TextView barName;
    private TextView barSub;
    private ImageButton barBtn;

    private SharedPreferences appPrefs;
    private Set<String> favs = new HashSet<>();
    private ExecutorService io;

    private int tab = TAB_ZH;
    private String query = "";
    private Runnable pendingFilter;
    private boolean loaded;

    private String playingUrl = "";
    private String playState = PlayerService.S_STOPPED;

    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appPrefs = getSharedPreferences("app", Context.MODE_PRIVATE);
        favs = new HashSet<>(appPrefs.getStringSet("favs", Collections.<String>emptySet()));
        io = Executors.newSingleThreadExecutor();

        list = findViewById(R.id.list);
        progress = findViewById(R.id.progress);
        empty = findViewById(R.id.empty);
        snapshotLabel = findViewById(R.id.snapshot);
        search = findViewById(R.id.search);
        bar = findViewById(R.id.bar);
        barName = findViewById(R.id.bar_name);
        barSub = findViewById(R.id.bar_sub);
        barBtn = findViewById(R.id.bar_btn);
        tabs = new TextView[]{findViewById(R.id.tab_zh), findViewById(R.id.tab_all), findViewById(R.id.tab_fav)};

        adapter = new StationAdapter(this, favs);
        adapter.setOnStarClick(this::toggleFav);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            hideKeyboard();
            play(adapter.getItem(position));
        });

        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            tabs[i].setOnClickListener(v -> selectTab(which));
        }
        barBtn.setOnClickListener(v -> sendToService(PlayerService.ACTION_TOGGLE));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                if (pendingFilter != null) ui.removeCallbacks(pendingFilter);
                pendingFilter = MainActivity.this::applyFilter;
                ui.postDelayed(pendingFilter, 160);
            }
        });

        registerStateReceiver();
        selectTab(TAB_ZH);
        syncFromService();

        progress.setVisibility(View.VISIBLE);
        io.execute(this::loadData);

        UpdateJob.schedule(this);
        UpdateJob.checkNow(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncFromService();
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
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------- 数据 ----------

    private void loadData() {
        try {
            Snapshot.Data d = Snapshot.load(getApplicationContext());
            ui.post(() -> {
                all.clear();
                all.addAll(d.stations);
                loaded = true;
                progress.setVisibility(View.GONE);
                snapshotLabel.setText(labelFor(d));
                applyFilter();
            });
        } catch (Exception e) {
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                empty.setText("读取内置数据失败：" + e.getMessage());
                empty.setVisibility(View.VISIBLE);
            });
        }
    }

    private String labelFor(Snapshot.Data d) {
        String at = d.generatedAt;
        String date = "";
        if (at.length() >= 10) date = at.substring(5, 7) + "-" + at.substring(8, 10);
        String base = all.size() + " 个台";
        return date.isEmpty() ? base : "数据 " + date + " · " + base;
    }

    private void selectTab(int which) {
        tab = which;
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == which;
            tabs[i].setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            tabs[i].setTextColor(getResources().getColor(on ? R.color.white : R.color.text_primary, getTheme()));
        }
        applyFilter();
    }

    private void applyFilter() {
        if (!loaded) return;
        visible.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Station s : all) {
            if (tab == TAB_ZH && !s.chinese) continue;
            if (tab == TAB_FAV && !favs.contains(s.url)) continue;
            if (!q.isEmpty() && !matches(s, q)) continue;
            visible.add(s);
        }
        adapter.setItems(new ArrayList<>(visible));
        if (visible.isEmpty()) {
            empty.setText(tab == TAB_FAV && q.isEmpty() ? R.string.empty_fav : R.string.empty_search);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
            list.setSelection(0);
        }
    }

    private boolean matches(Station s, String q) {
        return lower(s.name).contains(q) || lower(s.cc).contains(q) || lower(s.country).contains(q)
                || lower(s.tags).contains(q) || lower(s.codec).contains(q);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private void toggleFav(Station s) {
        if (favs.contains(s.url)) favs.remove(s.url);
        else favs.add(s.url);
        appPrefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        if (tab == TAB_FAV) applyFilter();
        else adapter.notifyDataSetChanged();
    }

    // ---------- 播放 ----------

    private void play(Station s) {
        ensureNotificationPermission();
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY)
                .putExtra(PlayerService.EXTRA_URL, s.url)
                .putExtra(PlayerService.EXTRA_NAME, s.displayName())
                .putExtra(PlayerService.EXTRA_SUB, s.meta());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        playingUrl = s.url;
        playState = PlayerService.S_CONNECTING;
        renderBar(s.displayName(), s.meta());
        adapter.setPlaying(playingUrl);
    }

    /** 只有 PLAY 走 startForegroundService（服务会立刻 startForeground）；
     *  TOGGLE / STOP 用普通 startService，否则 5 秒内不前进台会被系统判成 ANR。 */
    private void sendToService(String action) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        startService(i);
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void registerStateReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!PlayerService.BC_STATE.equals(intent.getAction())) return;
                playState = safe(intent.getStringExtra(PlayerService.EXTRA_STATE));
                String url = safe(intent.getStringExtra("url"));
                if (!url.isEmpty()) playingUrl = url;
                if (PlayerService.S_STOPPED.equals(playState)) {
                    bar.setVisibility(View.GONE);
                    adapter.setPlaying("");
                    return;
                }
                renderBar(safe(intent.getStringExtra("name")), safe(intent.getStringExtra("sub")));
                adapter.setPlaying(playingUrl);
                String err = intent.getStringExtra(PlayerService.EXTRA_ERROR);
                if (err != null) toast(err);
            }
        };
        IntentFilter f = new IntentFilter(PlayerService.BC_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    private void syncFromService() {
        playState = PlayerService.lastState;
        playingUrl = PlayerService.lastUrl;
        if (PlayerService.S_STOPPED.equals(playState) || playingUrl.isEmpty()) {
            bar.setVisibility(View.GONE);
            adapter.setPlaying("");
        } else {
            renderBar(PlayerService.lastName, PlayerService.lastSub);
            adapter.setPlaying(playingUrl);
        }
    }

    private void renderBar(String name, String sub) {
        bar.setVisibility(View.VISIBLE);
        barName.setText(name.isEmpty() ? "电台" : name);
        String state = stateText();
        barSub.setText(sub.isEmpty() ? state : state + " · " + sub);
        boolean playing = PlayerService.S_PLAYING.equals(playState) || PlayerService.S_CONNECTING.equals(playState);
        barBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        barBtn.setColorFilter(getResources().getColor(R.color.primary, getTheme()));
    }

    private String stateText() {
        if (PlayerService.S_CONNECTING.equals(playState)) return "正在连接…";
        if (PlayerService.S_PLAYING.equals(playState)) return "正在播放";
        if (PlayerService.S_PAUSED.equals(playState)) return "已暂停";
        if (PlayerService.S_ERROR.equals(playState)) return "播放失败";
        return "";
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
