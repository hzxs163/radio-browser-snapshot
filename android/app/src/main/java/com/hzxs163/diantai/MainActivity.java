package com.hzxs163.diantai;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int TAB_ZH = 0;
    private static final int TAB_ALL = 1;
    private static final int TAB_RECENT = 2;
    private static final int TAB_FAV = 3;

    private final List<Station> all = new ArrayList<>();
    private final List<Station> visible = new ArrayList<>();
    private final Map<String, Station> index = new HashMap<>();
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
    private String filterCountry = "";
    private String filterTag = "";
    private int filterMinBitrate = 0;
    private Runnable pendingFilter;
    private boolean loaded;

    private String playingUrl = "";
    private String playState = PlayerService.S_STOPPED;

    private BroadcastReceiver receiver;

    // 筛选弹层里的 chip 状态
    private final List<TextView> chipViews = new ArrayList<>();
    private final List<Checker> chipCheckers = new ArrayList<>();

    private interface Checker {
        boolean on();
    }

    private interface Value {
        String get();
    }

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
        tabs = new TextView[]{findViewById(R.id.tab_zh), findViewById(R.id.tab_all),
                findViewById(R.id.tab_recent), findViewById(R.id.tab_fav)};

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
        bar.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
        findViewById(R.id.filter).setOnClickListener(v -> showFilter());

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
                index.clear();
                for (Station s : all) index.put(s.url, s);
                UiBridge.byUrl = index;
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

        if (tab == TAB_RECENT) {
            for (String url : Recent.get(this)) {
                Station s = index.get(url);
                if (s != null && match(s, q)) visible.add(s);
            }
        } else {
            for (Station s : all) {
                if (tab == TAB_ZH && !s.chinese) continue;
                if (tab == TAB_FAV && !favs.contains(s.url)) continue;
                if (!match(s, q)) continue;
                visible.add(s);
            }
        }

        adapter.setItems(new ArrayList<>(visible));
        List<String> queue = new ArrayList<>(visible.size());
        for (Station s : visible) queue.add(s.url);
        UiBridge.queue = queue;

        if (visible.isEmpty()) {
            if (tab == TAB_FAV && q.isEmpty()) empty.setText(R.string.empty_fav);
            else if (tab == TAB_RECENT) empty.setText("还没有最近播放的台");
            else empty.setText(R.string.empty_search);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
            list.setSelection(0);
        }
    }

    private boolean match(Station s, String q) {
        if (!filterCountry.isEmpty() && !filterCountry.equals(s.country)) return false;
        if (filterMinBitrate > 0 && s.bitrate < filterMinBitrate) return false;
        if (!filterTag.isEmpty() && !s.tags.toLowerCase(Locale.ROOT).contains(filterTag.toLowerCase(Locale.ROOT)))
            return false;
        if (q.isEmpty()) return true;
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

    // ---------- 筛选弹层 ----------

    private void showFilter() {
        Dialog d = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));

        TextView title = new TextView(this);
        title.setText("筛选");
        title.setTextSize(19);
        title.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        chipViews.clear();
        chipCheckers.clear();

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        body.addView(section("国家"));
        addChipRow(body, countryOptions(), () -> filterCountry, v -> filterCountry = v);
        body.addView(section("流派"));
        addChipRow(body, tagOptions(), () -> filterTag, v -> filterTag = v);
        body.addView(section("码率"));
        addBitrateRow(body);

        scroll.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(10), 0, 0);

        TextView reset = actionButton("重置");
        reset.setOnClickListener(v -> {
            filterCountry = "";
            filterTag = "";
            filterMinBitrate = 0;
            refreshChips();
            applyFilter();
        });
        TextView done = actionButton("完成");
        done.setOnClickListener(v -> d.dismiss());

        actions.addView(reset);
        actions.addView(done);
        root.addView(actions);

        d.setContentView(root);
        refreshChips();
        d.show();
    }

    private TextView section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        t.setPadding(0, dp(14), 0, dp(8));
        return t;
    }

    private void addChipRow(LinearLayout parent, List<String[]> options,
                            Value current, Setter setter) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] opt : options) {
            final String value = opt[0];
            final String label = opt[1];
            TextView c = chip(label, () -> value.equals(current.get()));
            c.setOnClickListener(v -> {
                setter.set(value);
                refreshChips();
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            row.addView(c, lp);
        }
        hs.addView(row);
        parent.addView(hs);
    }

    private void addBitrateRow(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] values = {0, 64, 128};
        String[] labels = {"不限", "≥ 64k", "≥ 128k"};
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            TextView c = chip(labels[i], () -> filterMinBitrate == v);
            c.setOnClickListener(x -> {
                filterMinBitrate = v;
                refreshChips();
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            row.addView(c, lp);
        }
        hs.addView(row);
        parent.addView(hs);
    }

    private TextView chip(String label, Checker on) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackgroundResource(R.drawable.bg_chip);
        chipViews.add(t);
        chipCheckers.add(on);
        return t;
    }

    private void refreshChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            boolean on = chipCheckers.get(i).on();
            chipViews.get(i).setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            chipViews.get(i).setTextColor(getResources().getColor(
                    on ? R.color.white : R.color.text_primary, getTheme()));
        }
    }

    private TextView actionButton(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        t.setTextColor(getResources().getColor(R.color.primary, getTheme()));
        return t;
    }

    private interface Setter {
        void set(String value);
    }

    /** 国家/流派候选：按台数取前 24，加「全部」。 */
    private List<String[]> countryOptions() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Station s : all) {
            if (s.country.isEmpty()) continue;
            count.put(s.country, count.containsKey(s.country) ? count.get(s.country) + 1 : 1);
        }
        return topOptions(count, "全部", "");
    }

    private List<String[]> tagOptions() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Station s : all) {
            for (String t : s.tags.split(",")) {
                String v = t.trim();
                if (v.isEmpty()) continue;
                count.put(v, count.containsKey(v) ? count.get(v) + 1 : 1);
            }
        }
        return topOptions(count, "全部", "");
    }

    private List<String[]> topOptions(Map<String, Integer> count, String allLabel, String allValue) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(count.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{allValue, allLabel});
        for (int i = 0; i < Math.min(24, entries.size()); i++) {
            String v = entries.get(i).getKey();
            out.add(new String[]{v, v});
        }
        return out;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
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
        Recent.add(this, s.url);
        playingUrl = s.url;
        playState = PlayerService.S_CONNECTING;
        renderBar(s.displayName(), s.meta());
        adapter.setPlaying(playingUrl);
    }

    /** 只有 PLAY 走 startForegroundService（服务会立刻 startForeground）；
     *  TOGGLE / STOP / SLEEP 用普通 startService，否则 5 秒内不前进台会被系统判成 ANR。 */
    private void sendToService(String action) {
        startService(new Intent(this, PlayerService.class).setAction(action));
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
        String icy = PlayerService.lastTitle;
        String text;
        if (icy != null && !icy.isEmpty()) text = icy + " · " + stateText();
        else text = sub.isEmpty() ? stateText() : stateText() + " · " + sub;
        barSub.setText(text);
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
