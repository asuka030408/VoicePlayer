package com.yinsheng.player;

/** Conservative rule: never exclude Japanese kana or ambiguous short Han phrases. */
public final class SubtitleLanguage {
    private SubtitleLanguage() {}
    public static boolean isChinese(String text) {
        if (text == null) return false;
        int han = 0, letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\uff66' && c <= '\uff9f')) return false;
            if (Character.isLetter(c)) letters++;
            if ((c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf')) han++;
        }
        // Short all-Han phrases can be either Japanese or Chinese: translate conservatively.
        return han >= 12 && letters > 0 && han * 100 / letters >= 80;
    }
}
