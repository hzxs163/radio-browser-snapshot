package com.hzxs163.diantai;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 读 Icecast/Shoutcast 的 ICY 元数据拿「正在播放」的歌名。
 * 只额外开一条短连接：读到第一段元数据就断开，隔一会儿再来，
 * 不跟 MediaPlayer 抢同一条连接，也不会因为长时间挂着多占一份带宽。
 */
final class IcyReader {

    interface Listener {
        void onTitle(String title);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int INTERVAL_MS = 40000;

    private final String url;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile HttpURLConnection conn;
    private Thread thread;

    IcyReader(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    void start() {
        thread = new Thread(this::loop, "icy");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                readOnce();
            } catch (Exception ignored) {
                // 有些台根本不发 ICY，静默跳过
            }
            for (int i = 0; i < INTERVAL_MS / 500 && running; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void readOnce() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        conn = c;
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Icy-MetaData", "1");
            int metaInt = parseInt(c.getHeaderField("icy-metaint"));
            if (metaInt <= 0 || !running) return;
            try (InputStream in = c.getInputStream()) {
                if (!skip(in, metaInt)) return;
                int len = in.read();
                if (len <= 0) return;
                int size = len * 16;
                byte[] meta = new byte[size];
                if (!readFully(in, meta)) return;
                String title = streamTitle(new String(meta, StandardCharsets.UTF_8));
                if (running && title != null && !title.isEmpty()) listener.onTitle(title);
            }
        } finally {
            conn = null;
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean skip(InputStream in, int bytes) throws Exception {
        byte[] buf = new byte[4096];
        int left = bytes;
        while (left > 0 && running) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) return false;
            left -= n;
        }
        return running;
    }

    private boolean readFully(InputStream in, byte[] out) throws Exception {
        int off = 0;
        while (off < out.length && running) {
            int n = in.read(out, off, out.length - off);
            if (n < 0) return false;
            off += n;
        }
        return running;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String streamTitle(String meta) {
        int i = meta.indexOf("StreamTitle='");
        if (i < 0) return "";
        int start = i + "StreamTitle='".length();
        int end = meta.indexOf("';", start);
        String t = end > start ? meta.substring(start, end) : meta.substring(start);
        t = t.replace("\u0000", "").trim();
        return t;
    }
}
