package com.research.fuguroot;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * On-device front-end for the DirtyFrag chain: adb-wireless self-connect + `fuguquest --local`.
 *
 * The upstream no-adb app-chain (zygote preload walled it off — deleted in d89bfeb) was
 * refuted; this app drives option #1, the validated shell-domain chain, from the headset:
 *
 *   tap ROOT  -> mini adb client connects to 127.0.0.1:5555 (needs "ADB over network" on)
 *             -> unpacks fuguquest + targets/ from THIS apk into /data/local/tmp/fugu-root
 *             -> runs `fuguquest --local -t auto --adb-root`, output streamed below.
 *
 * Prereq: developer options -> ADB over network (once). On the first connect the headset
 * shows the usual "Allow USB debugging?" RSA confirmation — allow it (the app keeps its
 * own persistent key in filesDir, so it is a one-time tap).
 */
public class MainActivity extends Activity {

    private TextView log;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        Button btnRoot = new Button(this);
        btnRoot.setText("ROOT  (--local -t auto --adb-root)");
        Button btnId = new Button(this);
        btnId.setText("id / getenforce / build");

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setText("fugu root — on-device front-end\n\n"
                + "1) включи «ADB over network» в developer options шлема\n"
                + "2) жми ROOT; при первом заходе подтверди «Allow USB debugging?» на шлеме\n"
                + "3) цепочка мертва на security patch >= 2026-06-04 (см. README репо)\n\n");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-1, -2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(btnRoot, wrap);
        root.addView(btnId, wrap);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);

        btnRoot.setOnClickListener(v -> start("root"));
        btnId.setOnClickListener(v -> start("id"));
    }

    private void start(String what) {
        if (busy) { logLine("* busy\n"); return; }
        busy = true;
        new Thread(() -> flow(what)).start();
    }

    private void flow(String what) {
        AdbShell adb = null;
        try {
            adb = new AdbShell(getFilesDir(), s -> logLine("* " + s + "\n"));
            adb.connect("127.0.0.1", 5555);
            if (what.equals("id")) {
                logLine("\n== id / getenforce / build ==\n");
                adb.shell("id; getenforce; getprop ro.build.version.incremental; "
                        + "getprop ro.build.version.security_patch 2>&1", l -> logLine(l));
                return;
            }
            String apk = getPackageCodePath();
            logLine("\n$ deploy from " + apk + "\n");
            String cmd = "cd /data/local/tmp && rm -rf fugu-root && mkdir fugu-root && cd fugu-root"
                    + " && unzip -q -o \"" + apk + "\" 'assets/fugu/*'"
                    + " && cd assets/fugu && chmod 755 fuguquest"
                    + " && ./fuguquest --local -t auto --adb-root 2>&1";
            adb.shell(cmd, l -> logLine(l));
            logLine("\n== цепочка завершилась — проверь кнопкой id ==\n");
        } catch (final Throwable t) {
            logLine("\n[!] " + t + "\n"
                    + "    -> включи «ADB over network» в developer options шлема\n"
                    + "    -> при первом коннекте подтверди Allow на шлеме\n"
                    + "    -> на patch >= 2026-06-04 примитив мёртв\n");
        } finally {
            if (adb != null) adb.close();
            busy = false;
        }
    }

    private void logLine(final String s) {
        runOnUiThread(() -> log.append(s));
    }
}
