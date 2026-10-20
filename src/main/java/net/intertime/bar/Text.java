package net.intertime.bar;

import org.bukkit.Bukkit;

/**
 * Кольори без Adventure/MiniMessage - працює на Spigot і Paper від 1.16.5 до 26.x.
 * Формат у конфігу: &a &l &r ... та HEX: &#00e5ff
 */
final class Text {

    static final char S = '\u00A7';
    static final String CODES = "0123456789abcdef";
    static final boolean HEX = detectHex();

    private static final int[][] PAL = {
            {0, 0, 0}, {0, 0, 170}, {0, 170, 0}, {0, 170, 170},
            {170, 0, 0}, {170, 0, 170}, {255, 170, 0}, {170, 170, 170},
            {85, 85, 85}, {85, 85, 255}, {85, 255, 85}, {85, 255, 255},
            {255, 85, 85}, {255, 85, 255}, {255, 255, 85}, {255, 255, 255}
    };

    private Text() {
    }

    private static boolean detectHex() {
        try {
            String[] p = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
            int a = Integer.parseInt(p[0]);
            if (a > 1) return true;
            int b = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return b >= 16;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------ кольори

    static int parseColor(String s, int def) {
        try {
            return Integer.parseInt(s.replace("#", "").trim(), 16) & 0xFFFFFF;
        } catch (Exception e) {
            return def;
        }
    }

    static String hex(int rgb) {
        if (!HEX) return legacy(rgb);
        String h = String.format("%06x", rgb & 0xFFFFFF);
        StringBuilder sb = new StringBuilder(14).append(S).append('x');
        for (int i = 0; i < 6; i++) sb.append(S).append(h.charAt(i));
        return sb.toString();
    }

    /** Найближчий звичайний (16-колірний) код. */
    static String legacy(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int best = 0, bd = Integer.MAX_VALUE;
        for (int i = 0; i < PAL.length; i++) {
            int dr = r - PAL[i][0], dg = g - PAL[i][1], db = b - PAL[i][2];
            int d = dr * dr + dg * dg + db * db;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return "" + S + CODES.charAt(best);
    }

    private static boolean isHex(String s, int from, int len) {
        for (int i = from; i < from + len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** &-коди та &#rrggbb -> §-коди. */
    static String color(String in) {
        if (in == null) return "";
        int n = in.length();
        StringBuilder sb = new StringBuilder(n + 16);
        for (int i = 0; i < n; i++) {
            char c = in.charAt(i);
            if (c == '&' && i + 1 < n) {
                char d = in.charAt(i + 1);
                if (d == '#' && i + 8 <= n && isHex(in, i + 2, 6)) {
                    sb.append(hex(Integer.parseInt(in.substring(i + 2, i + 8), 16)));
                    i += 7;
                    continue;
                }
                char l = Character.toLowerCase(d);
                if (CODES.indexOf(l) >= 0 || (l >= 'k' && l <= 'o') || l == 'r') {
                    sb.append(S).append(l);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Градієнт з рухом. phase 0..2 зсуває хвилю. Якщо рядок виходить задовгим (ліміт заголовка),
     * колір міняється не на кожній літері, а на кожній 2-й, 3-й...
     */
    static String gradient(String text, int from, int to, double phase, boolean bold, int maxLen) {
        int len = text.length();
        for (int g = 1; g <= len; g++) {
            String s = buildGradient(text, from, to, phase, bold, g);
            if (s.length() <= maxLen || g == len) return s;
        }
        return text;
    }

    private static String buildGradient(String text, int from, int to, double phase, boolean bold, int g) {
        int len = text.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += g) {
            int end = Math.min(len, i + g);
            double pos = len <= 1 ? 0 : ((i + end - 1) / 2.0) / (len - 1);
            double x = (pos + phase) % 2.0;
            if (x > 1) x = 2 - x;
            sb.append(hex(lerp(from, to, x)));
            if (bold) sb.append(S).append('l');
            sb.append(text, i, end);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ розбиття для команд скорборда

    /** Найбільший індекс <= max, що не розриває код кольору. */
    static int cutIndex(String s, int max) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            int step = 1;
            if (s.charAt(i) == S && i + 1 < n) {
                step = (s.charAt(i + 1) == 'x' && i + 14 <= n) ? 14 : 2;
            }
            if (i + step > max) break;
            i += step;
        }
        return i;
    }

    static String lastColors(String s) {
        String color = "";
        StringBuilder fmt = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n - 1; i++) {
            if (s.charAt(i) != S) continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if (c == 'x' && i + 14 <= n) {
                color = s.substring(i, i + 14);
                fmt.setLength(0);
                i += 13;
            } else if (CODES.indexOf(c) >= 0) {
                color = "" + S + c;
                fmt.setLength(0);
                i++;
            } else if (c == 'r') {
                color = "";
                fmt.setLength(0);
                i++;
            } else if (c >= 'k' && c <= 'o') {
                fmt.append(S).append(c);
                i++;
            }
        }
        return color + fmt;
    }

    /** Ділить рядок на префікс і суфікс (по max символів кожен), переносячи колір. */
    static String[] split(String s, int max) {
        if (s.length() <= max) return new String[]{s, ""};
        int cut = cutIndex(s, max);
        String pre = s.substring(0, cut);
        String suf = lastColors(pre) + s.substring(cut);
        suf = suf.substring(0, cutIndex(suf, max));
        return new String[]{pre, suf};
    }

    static String prefix() {
        return gradient("INTERTIME", 0x00e5ff, 0x7c4dff, 0, true, 120) + " " + S + "8\u00BB " + S + "r";
    }
}
