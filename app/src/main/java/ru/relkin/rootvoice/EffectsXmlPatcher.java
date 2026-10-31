package ru.relkin.rootvoice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Добавляет эффект в audio_effects.xml (формат v2, Android 8+): библиотеку, эффект и
 * &lt;apply&gt; в &lt;preprocess&gt; для источников записи. Правка строковая, чтобы не трогать
 * остальное содержимое файла; комментарии удаляются, чтобы не попасть в закомментированные блоки.
 */
final class EffectsXmlPatcher {
    static final String NAME = "rootvoice";
    static final String LIB_FILE = "librootvoice.so";
    /** Источники записи, к которым применяется эффект (есть во всех версиях парсера с Android 8). */
    static final String[] SOURCES = {"mic", "voice_communication", "camcorder", "voice_recognition"};

    private EffectsXmlPatcher() {
    }

    static boolean isPatched(String xml) {
        return xml.contains("\"" + LIB_FILE + "\"");
    }

    static String patch(String xml, String effectUuid) {
        if (isPatched(xml)) return xml;
        if (!xml.contains("<audio_effects_conf")) throw new IllegalArgumentException("это не audio_effects.xml");
        String s = xml.replaceAll("(?s)<!--.*?-->", "");

        s = expandEmpty(s, "libraries");
        s = expandEmpty(s, "effects");
        s = insertBefore(s, "</libraries>", "    <library name=\"" + NAME + "\" path=\"" + LIB_FILE + "\"/>\n    ");
        s = insertBefore(s, "</effects>",
                "    <effect name=\"" + NAME + "\" library=\"" + NAME + "\" uuid=\"" + effectUuid + "\"/>\n    ");

        s = expandEmpty(s, "preprocess");
        if (!s.contains("</preprocess>")) {
            s = insertBefore(s, "</audio_effects_conf>", "    <preprocess>\n    </preprocess>\n");
        }
        for (String src : SOURCES) s = addToStream(s, src);
        return s;
    }

    private static String addToStream(String s, String type) {
        int start = s.indexOf("<preprocess>");
        int end = s.indexOf("</preprocess>", start);
        String region = s.substring(start, end);
        String apply = "<apply effect=\"" + NAME + "\"/>";
        Matcher m = Pattern.compile("<stream\\s+type\\s*=\\s*\"" + type + "\"\\s*(/?)>").matcher(region);
        String patched;
        if (m.find()) {
            if (m.group(1).equals("/")) {
                patched = region.substring(0, m.start()) + "<stream type=\"" + type + "\">\n            " + apply
                        + "\n        </stream>" + region.substring(m.end());
            } else {
                int close = region.indexOf("</stream>", m.end());
                if (close < 0) throw new IllegalArgumentException("незакрытый <stream type=\"" + type + "\">");
                patched = region.substring(0, close) + "    " + apply + "\n        " + region.substring(close);
            }
        } else {
            patched = region + "    <stream type=\"" + type + "\">\n            " + apply + "\n        </stream>\n    ";
        }
        return s.substring(0, start) + patched + s.substring(end);
    }

    private static String expandEmpty(String s, String tag) {
        return s.replaceFirst("<" + tag + "\\s*/>", "<" + tag + ">\n    </" + tag + ">");
    }

    private static String insertBefore(String s, String anchor, String text) {
        int i = s.lastIndexOf(anchor);
        if (i < 0) throw new IllegalArgumentException("в audio_effects.xml нет " + anchor);
        return s.substring(0, i) + text + s.substring(i);
    }
}
