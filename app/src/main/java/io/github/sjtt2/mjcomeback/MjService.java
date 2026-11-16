package io.github.sjtt2.mjcomeback;

import android.content.SharedPreferences;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;

/** 模块 App 侧的 LSPosed 服务句柄（由 {@link MjApp} 维护）。 */
public final class MjService {

    public interface Listener {
        void onServiceChanged(boolean connected);
    }

    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile XposedService service;

    private MjService() {
    }

    static void set(XposedService value) {
        service = value;
        for (Listener listener : LISTENERS) {
            try {
                listener.onServiceChanged(value != null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static XposedService get() {
        return service;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** 能写到宿主进程的那份配置；没有服务时为 null。 */
    public static SharedPreferences remotePrefs() {
        XposedService current = service;
        if (current == null) return null;
        try {
            return current.getRemotePreferences(MjSettings.PREFS_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
