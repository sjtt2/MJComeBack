package io.github.sjtt2.mjcomeback;

import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 触发判定。
 *
 * <p>原版彩蛋是**发送出去之后**才出现，所以这里也只在「发送」时播放，打字过程中不播。</p>
 *
 * <p>「发送」的判定不需要依赖抖音混淆过的业务类，用两个稳定信号：</p>
 * <ol>
 *     <li>用户点了发送按钮（{@code View#performClick}）或按了输入法发送键
 *         （{@code TextView#onEditorAction}）后 2.5s 内，输入框被清空，
 *         且清空前的内容正好是 mj 连写 —— 视为发送成功；</li>
 *     <li>输入框被**程序化** {@code setText("")} 清空（用户在键盘上手动删字不会走
 *         {@code setText}），且清空前内容匹配 —— 视为发送成功。</li>
 * </ol>
 */
final class MjEngine {

    /** 点击/发送键 与 输入框清空 之间的最大时间差。 */
    private static final long ARM_WINDOW_MS = 2500L;

    /** 两次播放之间的最小间隔，避免两条信号重复触发。 */
    private static final long COOLDOWN_MS = 1200L;

    /** 输入框当前「待发送」的匹配文本。 */
    private static final Map<EditText, String> PENDING =
            Collections.synchronizedMap(new WeakHashMap<EditText, String>());

    private static final Map<View, Long> LAST_FIRED =
            Collections.synchronizedMap(new WeakHashMap<View, Long>());

    private static volatile long armedAt;

    private MjEngine() {
    }

    /** 用户点了某个控件，或按下了输入法的发送 / 完成键。 */
    static void onUserSendIntent() {
        armedAt = SystemClock.uptimeMillis();
    }

    /** 每次文本变化：只记录状态，不播放。 */
    static void onTextChanged(EditText editText, Editable editable) {
        if (editText == null || editable == null) return;
        if (!MjSettings.enabled()) {
            PENDING.remove(editText);
            return;
        }

        String text = editable.toString();
        if (isBlank(text)) {
            String pending = PENDING.remove(editText);
            if (pending != null && armedRecently()) {
                play(editText);
            }
            return;
        }
        if (MjKeywords.matches(text, MjSettings.extraKeywords())) {
            PENDING.put(editText, text);
        } else {
            PENDING.remove(editText);
        }
    }

    /** 输入框被程序化清空（发送成功后 App 的典型动作）。 */
    static void onProgrammaticClear(EditText editText, String textBefore) {
        if (editText == null || textBefore == null) return;
        if (!MjSettings.enabled()) return;
        if (!MjKeywords.matches(textBefore, MjSettings.extraKeywords())) return;
        PENDING.remove(editText);
        play(editText);
    }

    private static boolean armedRecently() {
        long at = armedAt;
        return at != 0 && SystemClock.uptimeMillis() - at <= ARM_WINDOW_MS;
    }

    private static void play(EditText editText) {
        long now = SystemClock.uptimeMillis();
        Long last = LAST_FIRED.get(editText);
        if (last != null && now - last < COOLDOWN_MS) return;
        LAST_FIRED.put(editText, now);

        final boolean withSound = MjSettings.sound();
        final int mode = MjSettings.mode();
        editText.post(() -> {
            try {
                Context context = editText.getContext();
                if (context == null) return;
                View root = editText.getRootView();
                if (!(root instanceof ViewGroup)) return;
                MjOverlay.show((ViewGroup) root, context, MjOverlay.nextIndex(mode), withSound);
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean isBlank(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }
}
