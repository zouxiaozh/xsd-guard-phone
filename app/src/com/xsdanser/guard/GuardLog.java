package com.xsdanser.guard;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 内存环形日志 + 落盘（64 KiB 轮转）；UI 展示时按时间倒序，最新在最上。 */
final class GuardLog {
    private static final Object LOCK = new Object();
    private static final ArrayList<String> MEM = new ArrayList<String>();
    private static final int MAX = 400;
    private static Runnable listener;
    private static boolean loaded;

    static void setListener(Runnable r) {
        synchronized (LOCK) { listener = r; }
    }

    /** App 重启后把磁盘日志读回内存，倒序显示时历史也在。 */
    static void load(Context c) {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            if (!MEM.isEmpty()) return;
            FileInputStream in = null;
            try {
                File f = c.getFileStreamPath("guard.log");
                if (f == null || !f.isFile()) return;
                long skip = f.length() - 65536;
                in = new FileInputStream(f);
                if (skip > 0) in.skip(skip);
                byte[] buf = new byte[8192];
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                String[] lines = new String(bo.toByteArray(), "UTF-8").split("\n");
                int from = Math.max(0, lines.length - MAX);
                for (int i = from; i < lines.length; i++) {
                    String l = lines[i].trim();
                    if (l.length() > 0) MEM.add(l);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    static void i(Context c, String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg.replace('\n', ' ').replace('\r', ' ');
        synchronized (LOCK) {
            loaded = true;
            MEM.add(line);
            while (MEM.size() > MAX) MEM.remove(0);
        }
        try {
            File f = c.getFileStreamPath("guard.log");
            if (f != null && f.length() > 65536) c.deleteFile("guard.log");
            FileOutputStream fo = c.openFileOutput("guard.log", Context.MODE_APPEND);
            try { fo.write((line + "\n").getBytes("UTF-8")); }
            finally { fo.close(); }
        } catch (Throwable ignored) {}
        try { android.util.Log.i("XsdGuard", msg); } catch (Throwable ignored) {}
        fire();
    }

    /** 倒序拼接。limit > 0 时只给最新的 limit 行。 */
    static String text(int limit) {
        synchronized (LOCK) {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (int i = MEM.size() - 1; i >= 0; i--) {
                if (limit > 0 && n >= limit) break;
                if (n > 0) b.append('\n');
                b.append(MEM.get(i));
                n++;
            }
            if (b.length() == 0) b.append("（暂无日志）");
            return b.toString();
        }
    }

    static String text() { return text(0); }

    static int size() {
        synchronized (LOCK) { return MEM.size(); }
    }

    static void clear(Context c) {
        synchronized (LOCK) { MEM.clear(); loaded = true; }
        try { c.deleteFile("guard.log"); } catch (Throwable ignored) {}
        fire();
    }

    private static void fire() {
        Runnable r;
        synchronized (LOCK) { r = listener; }
        if (r != null) { try { r.run(); } catch (Throwable ignored) {} }
    }

    private GuardLog() {}
}
