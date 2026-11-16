package io.github.sjtt2.mjcomeback;

/**
 * 关键词匹配规则。
 *
 * <p>原版彩蛋的判定是「整段文本由若干个 mj 组成」，大小写不敏感：
 * {@code mj} / {@code MJ} / {@code mjMj} / {@code MJMJMJ} 都会触发，
 * 而 {@code mjmjm} / {@code abc mj} 不会。</p>
 */
public final class MjKeywords {

    private MjKeywords() {
    }

    public static boolean matches(String rawText, String extraKeywords) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return false;
        if (isMjChain(text)) return true;
        return matchesExtra(text, extraKeywords);
    }

    /** 整段文本必须是偶数长度，且每两个字符按 "mj" 配对。 */
    public static boolean isMjChain(String text) {
        int len = text.length();
        if (len < 2 || (len & 1) != 0) return false;
        for (int i = 0; i < len; i += 2) {
            char a = Character.toLowerCase(text.charAt(i));
            char b = Character.toLowerCase(text.charAt(i + 1));
            if (a != 'm' || b != 'j') return false;
        }
        return true;
    }

    private static boolean matchesExtra(String text, String extraKeywords) {
        if (extraKeywords == null || extraKeywords.isEmpty()) return false;
        for (String keyword : extraKeywords.split("[,，;；\\s]+")) {
            String k = keyword.trim();
            if (!k.isEmpty() && k.equalsIgnoreCase(text)) return true;
        }
        return false;
    }
}
