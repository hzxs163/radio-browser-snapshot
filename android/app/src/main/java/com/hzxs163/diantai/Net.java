package com.hzxs163.diantai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极小的 HTTP 客户端：只做「拿到整段字节」，不引任何第三方库。 */
final class Net {
    static final String UA = "DiantaI/1.0 (+https://github.com/hzxs163/radio-browser-snapshot)";

    private Net() {}

    static byte[] get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", UA);
            // 服务器返回的 .gz 本身就是压缩文件，别让框架再叠一层 gzip 解码
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
