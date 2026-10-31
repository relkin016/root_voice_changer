package ru.relkin.rootvoice;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Пользовательские пресеты: хранятся JSON-массивом в SharedPreferences. */
final class PresetStore {
    static final int MAX_NAME = 24;
    private static final String KEY = "user_presets";

    private final SharedPreferences prefs;
    private final List<VoiceParams.Preset> items = new ArrayList<>();

    PresetStore(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                items.add(new VoiceParams.Preset(o.getString("name"),
                        (float) o.optDouble("pitch", 0), (float) o.optDouble("rough", 0),
                        (float) o.optDouble("robot", 0), (float) o.optDouble("echo", 0),
                        o.optBoolean("radio", false), (float) o.optDouble("gain", 0), true));
            }
        } catch (JSONException e) {
            items.clear();
        }
    }

    List<VoiceParams.Preset> list() {
        return items;
    }

    /** Встроенные, затем пользовательские. */
    List<VoiceParams.Preset> all() {
        List<VoiceParams.Preset> all = new ArrayList<>(Arrays.asList(VoiceParams.PRESETS));
        all.addAll(items);
        return all;
    }

    static boolean isBuiltInName(String name) {
        for (VoiceParams.Preset p : VoiceParams.PRESETS) {
            if (p.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    VoiceParams.Preset find(String name) {
        for (VoiceParams.Preset p : items) {
            if (p.name.equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** Добавляет пресет или заменяет пользовательский с тем же именем (на его месте). */
    void put(VoiceParams.Preset p) {
        VoiceParams.Preset old = find(p.name);
        if (old != null) items.set(items.indexOf(old), p);
        else items.add(p);
        save();
    }

    /** Заменяет old на p, сохраняя позицию. */
    void replace(VoiceParams.Preset old, VoiceParams.Preset p) {
        int i = items.indexOf(old);
        if (i < 0) return;
        items.set(i, p);
        save();
    }

    void remove(VoiceParams.Preset p) {
        items.remove(p);
        save();
    }

    String nextName() {
        for (int n = items.size() + 1; ; n++) {
            String name = "Мой пресет " + n;
            if (find(name) == null) return name;
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (VoiceParams.Preset p : items) {
                a.put(new JSONObject().put("name", p.name).put("pitch", p.pitch).put("rough", p.rough)
                        .put("robot", p.robot).put("echo", p.echo).put("radio", p.radio).put("gain", p.gainDb));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY, a.toString()).apply();
    }
}
