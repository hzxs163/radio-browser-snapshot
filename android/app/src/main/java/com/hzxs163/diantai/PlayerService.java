package com.hzxs163.diantai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** 前台播放服务：MediaPlayer + 通知栏/锁屏控制，切后台继续出声。 */
public class PlayerService extends Service {

    static final String ACTION_PLAY = "com.hzxs163.diantai.action.PLAY";
    static final String ACTION_TOGGLE = "com.hzxs163.diantai.action.TOGGLE";
    static final String ACTION_STOP = "com.hzxs163.diantai.action.STOP";

    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_SUB = "sub";

    static final String BC_STATE = "com.hzxs163.diantai.STATE";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_ERROR = "error";

    static final String S_CONNECTING = "connecting";
    static final String S_PLAYING = "playing";
    static final String S_PAUSED = "paused";
    static final String S_ERROR = "error";
    static final String S_STOPPED = "stopped";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1001;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private WifiManager.WifiLock wifiLock;
    private Object focusRequest; // API 26+ 用 AudioFocusRequest，低版本走旧接口

    private String currentUrl = "";
    private String currentName = "";
    private String currentSub = "";
    private String state = S_STOPPED;

    // 同进程内的「上次状态」，Activity 重建时不用重新问服务
    static volatile String lastState = S_STOPPED;
    static volatile String lastUrl = "";
    static volatile String lastName = "";
    static volatile String lastSub = "";

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        session = new MediaSession(this, "diantai");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && !url.isEmpty()) {
                currentName = safe(intent.getStringExtra(EXTRA_NAME));
                currentSub = safe(intent.getStringExtra(EXTRA_SUB));
                play(url);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (S_PLAYING.equals(state) || S_CONNECTING.equals(state)) pause();
            else if (S_PAUSED.equals(state)) resume();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    // ---------- 播放 ----------

    private void play(String url) {
        boolean same = url.equals(currentUrl) && player != null;
        currentUrl = url;
        if (same && S_PAUSED.equals(state)) {
            resume();
            return;
        }
        releasePlayer();
        setState(S_CONNECTING, null);
        startForegroundCompat(buildNotification());
        acquireLocks();
        if (!requestFocus()) {
            setState(S_ERROR, "音频被其他应用占用");
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                mp.start();
                setState(S_PLAYING, null);
            });
            player.setOnCompletionListener(mp -> {
                if (mp == player) setState(S_STOPPED, null);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w("diantai", "media error " + what + "/" + extra + " " + currentUrl);
                if (mp == player) setState(S_ERROR, "这条线路放不出来，换一个台试试");
                return true;
            });
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) setState(S_CONNECTING, null);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && mp.isPlaying()) setState(S_PLAYING, null);
                return false;
            });
            player.setDataSource(url);
            player.prepareAsync();
        } catch (Exception e) {
            Log.w("diantai", "play failed", e);
            setState(S_ERROR, "打不开这个台：" + e.getMessage());
        }
    }

    private void resume() {
        if (player == null) {
            if (!currentUrl.isEmpty()) play(currentUrl);
            return;
        }
        if (!requestFocus()) return;
        try {
            player.start();
            setState(S_PLAYING, null);
        } catch (Exception e) {
            setState(S_ERROR, "继续播放失败");
        }
    }

    private void pause() {
        if (player == null) return;
        if (S_CONNECTING.equals(state)) {
            // 还在连：直接丢弃这次连接，重按播放会从头再来
            releasePlayer();
            releaseLocks();
            setState(S_PAUSED, null);
            return;
        }
        if (player.isPlaying()) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
            setState(S_PAUSED, null);
        }
    }

    private void stopPlayback() {
        releasePlayer();
        currentUrl = "";
        setState(S_STOPPED, null);
        broadcast();
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
        }
        stopForeground(true);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ---------- 状态 / 广播 / 通知 ----------

    private void setState(String s, String error) {
        state = s;
        lastState = s;
        lastUrl = currentUrl;
        lastName = currentName;
        lastSub = currentSub;
        if (session != null) {
            int pb = PlaybackState.STATE_NONE;
            if (S_PLAYING.equals(s)) pb = PlaybackState.STATE_PLAYING;
            else if (S_PAUSED.equals(s)) pb = PlaybackState.STATE_PAUSED;
            else if (S_CONNECTING.equals(s)) pb = PlaybackState.STATE_BUFFERING;
            else if (S_ERROR.equals(s)) pb = PlaybackState.STATE_ERROR;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(pb, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentName)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSub)
                    .build());
        }
        if (S_STOPPED.equals(s) || S_ERROR.equals(s)) {
            releaseLocks();
            abandonFocus();
            if (S_STOPPED.equals(s)) return;
        }
        broadcast(error);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private void broadcast() {
        broadcast(null);
    }

    private void broadcast(String error) {
        Intent i = new Intent(BC_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra("url", currentUrl);
        i.putExtra("name", currentName);
        i.putExtra("sub", currentSub);
        if (error != null) i.putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "播放控制",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean playing = S_PLAYING.equals(state) || S_CONNECTING.equals(state);
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        String label = playing ? "暂停" : "播放";

        Notification.Action toggle = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), label,
                serviceIntent(ACTION_TOGGLE, 1)).build();
        Notification.Action stop = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop), "停止",
                serviceIntent(ACTION_STOP, 2)).build();

        String title = currentName.isEmpty() ? "电台" : currentName;
        String text = currentSub.isEmpty() ? stateText() : currentSub + " · " + stateText();

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(toggle)
                .addAction(stop);
        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1));
        }
        return b.build();
    }

    private String stateText() {
        if (S_CONNECTING.equals(state)) return "正在连接…";
        if (S_PLAYING.equals(state)) return "正在播放";
        if (S_PAUSED.equals(state)) return "已暂停";
        if (S_ERROR.equals(state)) return "播放失败";
        return "";
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------- 音频焦点 / 锁 ----------

    private boolean requestFocus() {
        if (audio == null) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onFocus)
                    .build();
            focusRequest = req;
            return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audio.requestAudioFocus(this::onFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest instanceof AudioFocusRequest) {
            audio.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
        } else {
            audio.abandonAudioFocus(this::onFocus);
        }
        focusRequest = null;
    }

    private void onFocus(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "diantai:stream");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        wifiLock = null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        releaseLocks();
        abandonFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
