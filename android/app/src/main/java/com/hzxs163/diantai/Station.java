package com.hzxs163.diantai;

/** 一条电台记录。字段与 data 分支 stations.web.json 的 12 列一一对应，字典已展开。 */
public final class Station {
    public final String name;
    public final String url;
    public final String cc;
    public final String country;
    public final String lang;
    public final String tags;
    public final int votes;
    public final int bitrate;
    public final String codec;
    public final boolean hls;
    public final boolean chinese;

    public Station(String name, String url, String cc, String country, String lang, String tags,
                   int votes, int bitrate, String codec, boolean hls, boolean chinese) {
        this.name = name;
        this.url = url;
        this.cc = cc;
        this.country = country;
        this.lang = lang;
        this.tags = tags;
        this.votes = votes;
        this.bitrate = bitrate;
        this.codec = codec;
        this.hls = hls;
        this.chinese = chinese;
    }

    /** 名称缺失的台在官方库里不少，用域名兜底，免得列表里一排空行。 */
    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String host = hostOf(url);
        return host.isEmpty() ? "未命名电台" : host;
    }

    /** 列表第二行：国家 · 流派 · 码率。空字段直接跳过。 */
    public String meta() {
        StringBuilder sb = new StringBuilder();
        if (!country.isEmpty()) sb.append(country);
        if (!tags.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(tags.length() > 28 ? tags.substring(0, 28) + "…" : tags);
        }
        if (bitrate > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(bitrate).append("k");
        }
        if (sb.length() == 0) sb.append(codec.isEmpty() ? "电台" : codec);
        return sb.toString();
    }

    static String hostOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        String rest = i >= 0 ? url.substring(i + 3) : url;
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int colon = rest.indexOf(':');
        if (colon >= 0) rest = rest.substring(0, colon);
        return rest;
    }

    /** 「中文台」口径：港澳台+大陆，或语言标了中文/粤语/国语。 */
    static boolean isChinese(String cc, String lang) {
        if ("CN".equals(cc) || "TW".equals(cc) || "HK".equals(cc) || "MO".equals(cc)) return true;
        String l = lang == null ? "" : lang.toLowerCase();
        return l.contains("chinese") || l.contains("mandarin") || l.contains("cantonese");
    }
}
