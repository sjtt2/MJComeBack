package io.github.sjtt2.mjcomeback;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 抖音「MJ 彩蛋」回归模块（libxposed API 102 / LSPosed 现代 API）。
 *
 * <p>不依赖抖音内部已被撤下的彩蛋配置，而是在输入框层面做关键词嗅探：
 * 文本匹配「mj」连写（mj / mjmj / MJMJMJ …）时，在当前窗口顶层播放蜘蛛侠动画 + 原版音效。</p>
 */
public final class ModuleMain extends XposedModule {

    private static final String TAG = "MJComeback";

    private static final String[] TARGET_PACKAGES = {
            "com.ss.android.ugc.aweme",   // 抖音
            "com.ss.android.ugc.trill",   // TikTok
            "com.zhiliaoapp.musically"    // TikTok Global
    };

    private static final Set<EditText> WATCHED =
            Collections.newSetFromMap(new WeakHashMap<EditText, Boolean>());

    private static final Set<String> LOGGED_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());

    private String processName;
    private volatile boolean installed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "loaded in " + processName
                + " | framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " | api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            String pkg = param.getPackageName();
            if (!isTarget(pkg) || !isWantedProcess()) {
                log(Log.INFO, TAG, "skip " + pkg + " / " + processName);
                detachSafely();
                return;
            }

            log(Log.INFO, TAG, "attaching to " + pkg + " (" + processName + ")");
            MjAssets.init(getModuleApplicationInfo());

            SharedPreferences prefs = null;
            try {
                prefs = getRemotePreferences(MjSettings.PREFS_NAME);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "remote preferences unavailable: " + t);
            }
            MjSettings.bind(prefs);

            install();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageReady failed: " + t);
        }
    }

    private static boolean isTarget(String pkg) {
        for (String target : TARGET_PACKAGES) {
            if (target.equals(pkg)) return true;
        }
        return false;
    }

    /**
     * 除了明确用不到的重量级子进程（push / sandbox / miniapp / webview…）都挂上。
     * 用黑名单而不是白名单：猜错的代价是「私信里静默没反应」，比多挂几个进程严重得多。
     */
    private boolean isWantedProcess() {
        String name = processName;
        if (name == null) return true;
        int idx = name.indexOf(':');
        if (idx < 0) return true;
        String suffix = name.substring(idx + 1).toLowerCase(Locale.ROOT);
        String[] ignored = {"push", "sandbox", "miniapp", "webview", "lynx", "patch",
                "download", "espresso", "music", "widget"};
        for (String keyword : ignored) {
            if (suffix.contains(keyword)) return false;
        }
        return true;
    }

    private void detachSafely() {
        try {
            if (getApiVersion() >= 102) {
                detach();
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "detach failed: " + t);
        }
    }

    private synchronized void install() {
        if (installed) return;
        installed = true;
        hookEditTextConstructors();
        hookWindowScans();
        hookUserSendIntents();
        hookProgrammaticClear();
    }

    /**
     * 发送意图信号：点击（发送按钮 / 发送箭头）与输入法「发送 / 完成」键。
     * 只记录时间戳，真正的播放要等输入框被清空才发生。
     */
    private void hookUserSendIntents() {
        try {
            Method performClick = View.class.getDeclaredMethod("performClick");
            hook(performClick).intercept(chain -> {
                try {
                    MjEngine.onUserSendIntent();
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook View#performClick failed: " + t);
        }

        try {
            Method onEditorAction = TextView.class.getDeclaredMethod("onEditorAction", int.class);
            hook(onEditorAction).intercept(chain -> {
                try {
                    MjEngine.onUserSendIntent();
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook TextView#onEditorAction failed: " + t);
        }
    }

    /**
     * 第二个发送信号：输入框被程序化 {@code setText("")} 清空。
     * 用户在键盘上手动删字走的是 Editable 修改，不会经过 setText，所以不会误判。
     */
    private void hookProgrammaticClear() {
        try {
            Method setText = TextView.class.getDeclaredMethod(
                    "setText", CharSequence.class, TextView.BufferType.class);
            hook(setText).intercept(chain -> {
                Object self = chain.getThisObject();
                boolean isEditText = self instanceof EditText;
                CharSequence next = null;
                CharSequence before = null;
                if (isEditText) {
                    try {
                        next = (CharSequence) chain.getArg(0);
                        before = ((EditText) self).getText();
                    } catch (Throwable ignored) {
                    }
                }
                Object result = chain.proceed();
                if (isEditText && before != null && isEmpty(next)) {
                    try {
                        MjEngine.onProgrammaticClear((EditText) self, before.toString());
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "clear hook failed: " + t);
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook TextView#setText failed: " + t);
        }
    }

    private static boolean isEmpty(CharSequence text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 主路径：拦截 EditText 构造，构造完成后挂 TextWatcher。
     * 抖音的评论输入框 / 私信输入框都继承自 android.widget.EditText。
     */
    private void hookEditTextConstructors() {
        int count = 0;
        for (Constructor<?> ctor : EditText.class.getDeclaredConstructors()) {
            try {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof EditText) {
                            installWatcher((EditText) self);
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "ctor hook body failed: " + t);
                    }
                    return result;
                });
                count++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook EditText ctor failed: " + t);
            }
        }
        log(Log.INFO, TAG, "EditText constructors hooked: " + count);
    }

    /**
     * 兜底路径：每次页面 / 弹窗出现时扫一遍视图树，补挂漏掉的输入框
     * （比如在模块注入之前就已创建的控件）。成本远低于 hook View#onAttachedToWindow。
     */
    private void hookWindowScans() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Window window = ((Activity) self).getWindow();
                        if (window != null) scan(window.getDecorView());
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "activity scan failed: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook Activity#onResume failed: " + t);
        }

        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            hook(show).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Dialog) {
                        Window window = ((Dialog) self).getWindow();
                        if (window != null) scan(window.getDecorView());
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "dialog scan failed: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook Dialog#show failed: " + t);
        }
    }

    private void scan(View view) {
        if (view == null) return;
        if (view instanceof EditText) {
            installWatcher((EditText) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                scan(group.getChildAt(i));
            }
        }
    }

    private void installWatcher(EditText editText) {
        synchronized (WATCHED) {
            if (!WATCHED.add(editText)) return;
        }
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    MjEngine.onTextChanged(editText, s);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "text watcher failed: " + t);
                }
            }
        });

        String className = editText.getClass().getName();
        if (LOGGED_CLASSES.add(className)) {
            log(Log.INFO, TAG, "watcher installed on " + className);
        }
    }
}
