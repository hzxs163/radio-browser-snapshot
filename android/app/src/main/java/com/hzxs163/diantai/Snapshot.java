package com.hzxs163.diantai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 快照的取用顺序：本机更新过的那份 → 包内 asset。
 * 包内 asset 与线上用的是同一种文件（data 分支的 stations.web.json.gz），
 * 所以解析器只有一份；解析结果落成定长二进制缓存，之后每次启动只读缓存。
 */
public final class Snapshot {

    static final String BASE = "https://hzxs163.github.io/radio-browser-snapshot/";
    static final String ASSET_GZ = "stations.web.json.gz";
    static final String ASSET_META = "stations.meta.json";
    static final String CACHE = "stations.cache.v1";
    static final String META = "snapshot.meta";

    private static final int MAGIC = 0x44415431; // "DAT1"
    private static final int FORMAT = 1;

    private Snapshot() {}

    public static final class Data {
        public final List<Station> stations;
        public final String generatedAt;
        public final boolean fromCache;

        Data(List<Station> s, String at, boolean cache) {
            this.stations = s;
            this.generatedAt = at;
            this.fromCache = cache;
        }
    }

    public static final class Meta {
        public final String sha256;
        public final String generatedAt;
        public final int rows;

        Meta(String sha256, String generatedAt, int rows) {
            this.sha256 = sha256 == null ? "" : sha256;
            this.generatedAt = generatedAt == null ? "" : generatedAt;
            this.rows = rows;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("sha256", sha256);
                o.put("generatedAt", generatedAt);
                o.put("rows", rows);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Meta fromJson(String raw) {
            try {
                JSONObject o = new JSONObject(raw);
                return new Meta(o.optString("sha256"), o.optString("generatedAt"), o.optInt("rows"));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 启动路径：缓存优先，没有就解析包内快照并写缓存。 */
    public static Data load(Context ctx) throws Exception {
        File cache = new File(ctx.getFilesDir(), CACHE);
        Meta local = readLocalMeta(ctx);
        if (cache.isFile() && local != null) {
            try {
                List<Station> list = readCache(cache);
                if (!list.isEmpty()) return new Data(list, local.generatedAt, true);
            } catch (Exception ignored) {
                // 缓存坏了就当没有，下面回退到包内快照
            }
        }
        Meta bundled = readBundledMeta(ctx);
        List<Station> list;
        try (InputStream in = ctx.getAssets().open(ASSET_GZ)) {
            list = parse(in);
        }
        sort(list);
        writeCacheAtomic(ctx, list);
        if (bundled != null) saveLocalMeta(ctx, bundled);
        return new Data(list, bundled == null ? "" : bundled.generatedAt, false);
    }

    /** 后台更新：校验过的 gz 字节 → 解析 → 原子替换缓存。失败时旧缓存原样保留。 */
    public static int install(Context ctx, byte[] gz, Meta meta) throws Exception {
        List<Station> list = parse(new java.io.ByteArrayInputStream(gz));
        sort(list);
        writeCacheAtomic(ctx, list);
        saveLocalMeta(ctx, meta);
        return list.size();
    }

    public static Meta readBundledMeta(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET_META)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return null;
            JSONObject o = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            return new Meta(o.optString("sha256_gz"), o.optString("generated_at"), o.optInt("web_rows"));
        } catch (Exception e) {
            return null;
        }
    }

    public static Meta readLocalMeta(Context ctx) {
        File f = new File(ctx.getFilesDir(), META);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n > 0 ? Meta.fromJson(new String(buf, 0, n, StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveLocalMeta(Context ctx, Meta meta) {
        File tmp = new File(ctx.getFilesDir(), META + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(meta.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        File dst = new File(ctx.getFilesDir(), META);
        if (tmp.renameTo(dst)) return;
        // 某些设备 rename 覆盖会失败，退化成先删再改名
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(dst);
    }

    // ---------- 解析 ----------

    static List<Station> parse(InputStream raw) throws Exception {
        List<Station> out = new ArrayList<>(70000);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new BufferedInputStream(raw)), StandardCharsets.UTF_8), 1 << 16)) {
            String header = r.readLine();
            if (header == null) return out;
            JSONObject h = new JSONObject(header);
            JSONObject dict = h.getJSONObject("dict");
            String[] cc = dictArray(dict, "cc");
            String[] country = dictArray(dict, "country");
            String[] lang = dictArray(dict, "lang");
            String[] codec = dictArray(dict, "codec");
            String[] tags = dictArray(dict, "tags");

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONArray a;
                try {
                    a = new JSONArray(line);
                } catch (Exception bad) {
                    continue;
                }
                String url = a.optString(1, "");
                if (url.isEmpty()) continue;
                String name = a.optString(0, "");
                String c = pick(cc, a.optInt(2, -1));
                String co = pick(country, a.optInt(3, -1));
                String la = pick(lang, a.optInt(4, -1));
                String tg = joinTags(a.optJSONArray(5), tags);
                int votes = a.optInt(6, 0);
                int bitrate = a.optInt(7, 0);
                String cd = pick(codec, a.optInt(8, -1));
                boolean hls = a.optInt(9, 0) != 0;
                out.add(new Station(name, url, c, co, la, tg, votes, bitrate, cd, hls,
                        Station.isChinese(c, la)));
            }
        }
        return out;
    }

    private static String[] dictArray(JSONObject dict, String key) {
        JSONArray a = dict.optJSONArray(key);
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optString(i, "");
        return out;
    }

    private static String pick(String[] dict, int idx) {
        return idx >= 0 && idx < dict.length ? dict[idx] : "";
    }

    private static String joinTags(JSONArray idx, String[] dict) {
        if (idx == null || idx.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < idx.length(); i++) {
            String t = pick(dict, idx.optInt(i, -1));
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
            if (sb.length() > 64) break;
        }
        return sb.toString();
    }

    /** 票数高的前头，同票按名字，保证每次启动顺序稳定。 */
    static void sort(List<Station> list) {
        Collections.sort(list, (x, y) -> {
            if (x.votes != y.votes) return Integer.compare(y.votes, x.votes);
            return x.displayName().compareToIgnoreCase(y.displayName());
        });
    }

    // ---------- 二进制缓存 ----------

    private static void writeCacheAtomic(Context ctx, List<Station> list) throws IOException {
        File tmp = new File(ctx.getFilesDir(), CACHE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(list.size());
            for (Station s : list) {
                out.writeUTF(s.name);
                out.writeUTF(s.url);
                out.writeUTF(s.cc);
                out.writeUTF(s.country);
                out.writeUTF(s.lang);
                out.writeUTF(s.tags);
                out.writeInt(s.votes);
                out.writeInt(s.bitrate);
                out.writeUTF(s.codec);
                out.writeBoolean(s.hls);
                out.writeBoolean(s.chinese);
            }
            out.flush();
        }
        File dst = new File(ctx.getFilesDir(), CACHE);
        if (tmp.renameTo(dst)) return;
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("cache rename failed");
    }

    private static List<Station> readCache(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("bad magic");
            if (in.readInt() != FORMAT) throw new IOException("bad format");
            int n = in.readInt();
            if (n < 0 || n > 200000) throw new IOException("bad count");
            List<Station> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new Station(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                        in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readUTF(),
                        in.readBoolean(), in.readBoolean()));
            }
            return out;
        }
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
