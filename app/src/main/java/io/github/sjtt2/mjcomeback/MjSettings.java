package io.github.sjtt2.mjcomeback;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * 模块设置。
 *
 * <p>宿主进程读的是 libxposed 的 Remote Preferences（存放在 LSPosed 数据库里）；
 * 模块 App 通过 {@code libxposed/service} 的 {@code XposedService#getRemotePreferences} 写同一份数据。
 * 没有服务时（例如不挂 LSPosed 直接打开模块 App）降级为本地 SharedPreferences，仅用于预览。</p>
 */
public final class MjSettings {

    private static final String TAG = "MJSettings";

    public static final String PREFS_NAME = "mj_config";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SOUND = "sound";
    public static final String KEY_MODE = "mode";
    public static final String KEY_EXTRA_KEYWORDS = "extra_keywords";

    /** 播放顺序：0 = 交替（吊下来 / 荡秋千 轮换），1 = 随机。 */
    public static final int MODE_ALTERNATE = 0;
    public static final int MODE_RANDOM = 1;

    private static volatile SharedPreferences prefs;
    /** 必须强引用：SharedPreferencesImpl 用 WeakHashMap 存监听器，lambda 被 GC 后配置会静默失效。 */
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static volatile boolean enabled = true;
    private static volatile boolean sound = true;
    private static volatile int mode = MODE_ALTERNATE;
    private static volatile String extraKeywords = "";

    private MjSettings() {
    }

    /** 绑定配置来源（宿主进程传 Remote Preferences，模块 App 传本地 prefs）。 */
    public static void bind(SharedPreferences p) {
        SharedPreferences old = prefs;
        SharedPreferences.OnSharedPreferenceChangeListener oldListener = listener;
        if (old != null && oldListener != null) {
            try {
                old.unregisterOnSharedPreferenceChangeListener(oldListener);
            } catch (Throwable ignored) {
            }
        }
        prefs = p;
        if (p != null) {
            try {
                SharedPreferences.OnSharedPreferenceChangeListener created = (sp, key) -> reload();
                listener = created;
                p.registerOnSharedPreferenceChangeListener(created);
            } catch (Throwable t) {
                Log.w(TAG, "register listener failed: " + t);
            }
        }
        reload();
    }

    private static void reload() {
        SharedPreferences p = prefs;
        if (p == null) {
            enabled = true;
            sound = true;
            mode = MODE_ALTERNATE;
            extraKeywords = "";
            return;
        }
        try {
            enabled = p.getBoolean(KEY_ENABLED, true);
            sound = p.getBoolean(KEY_SOUND, true);
            mode = p.getInt(KEY_MODE, MODE_ALTERNATE);
            String extra = p.getString(KEY_EXTRA_KEYWORDS, "");
            extraKeywords = extra == null ? "" : extra;
        } catch (Throwable t) {
            Log.w(TAG, "reload failed: " + t);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean sound() {
        return sound;
    }

    public static int mode() {
        return mode;
    }

    public static String extraKeywords() {
        return extraKeywords;
    }
}
