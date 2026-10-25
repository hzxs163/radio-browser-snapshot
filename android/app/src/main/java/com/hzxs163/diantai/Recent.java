package com.hzxs163.diantai;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 最近播放：最多 30 条，最新在前。 */
final class Recent {
    private static final int MAX = 30;

    private Recent() {}

    static List<String> get(Context ctx) {
        String raw = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("recent", "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
        } catch (Exception ignored) {
        }
        out.remove("");
        return out;
    }

    static void add(Context ctx, String url) {
        if (url == null || url.isEmpty()) return;
        List<String> list = get(ctx);
        list.remove(url);
        list.add(0, url);
        while (list.size() > MAX) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                .putString("recent", a.toString()).apply();
    }
}
