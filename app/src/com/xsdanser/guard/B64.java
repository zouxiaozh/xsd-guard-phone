package com.xsdanser.guard;

/** 自带 Base64：java.util.Base64 要 API 26，android.util.Base64 在 PC 端不可用。 */
final class B64 {
    private static final char[] T =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String encode(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= d.length) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            sb.append(T[(v >>> 18) & 63]).append(T[(v >>> 12) & 63])
              .append(T[(v >>> 6) & 63]).append(T[v & 63]);
            i += 3;
        }
        int rest = d.length - i;
        if (rest == 1) {
            int v = (d[i] & 0xff) << 16;
            sb.append(T[(v >>> 18) & 63]).append(T[(v >>> 12) & 63]).append("==");
        } else if (rest == 2) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            sb.append(T[(v >>> 18) & 63]).append(T[(v >>> 12) & 63])
              .append(T[(v >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    static byte[] decode(String s) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' ) break;
            if (c != '\r' && c != '\n' && c != ' ' && c != '\t') clean.append(c);
        }
        String t = clean.toString();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i + 4 <= t.length()) {
            int a = idx(t.charAt(i)), b = idx(t.charAt(i + 1)),
                c = idx(t.charAt(i + 2)), d = idx(t.charAt(i + 3));
            int v = (a << 18) | (b << 12) | (c << 6) | d;
            out.write((v >>> 16) & 0xff);
            out.write((v >>> 8) & 0xff);
            out.write(v & 0xff);
            i += 4;
        }
        int rest = t.length() - i;
        if (rest == 2) {
            int v = (idx(t.charAt(i)) << 18) | (idx(t.charAt(i + 1)) << 12);
            out.write((v >>> 16) & 0xff);
        } else if (rest == 3) {
            int v = (idx(t.charAt(i)) << 18) | (idx(t.charAt(i + 1)) << 12)
                  | (idx(t.charAt(i + 2)) << 6);
            out.write((v >>> 16) & 0xff);
            out.write((v >>> 8) & 0xff);
        }
        return out.toByteArray();
    }

    private static int idx(char c) {
        for (int i = 0; i < T.length; i++) if (T[i] == c) return i;
        return 0;
    }

    private B64() {}
}
