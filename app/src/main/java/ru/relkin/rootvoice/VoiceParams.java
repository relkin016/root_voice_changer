package ru.relkin.rootvoice;

import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/** Параметры голоса. Сериализуются в строку, которую читает DSP (rvc_parse_params). */
final class VoiceParams {
    boolean enabled = true;
    float pitch;      // полутоны [-12..12]
    float rough;      // [-1..1]
    float robot;      // [0..1]
    float echo;       // [0..1]
    boolean radio;
    float gainDb;     // [-12..12]

    static final class Preset {
        final String name;
        final float pitch, rough, robot, echo, gainDb;
        final boolean radio;
        /** Пресет создан пользователем (можно переименовать, перезаписать и удалить). */
        final boolean custom;

        Preset(String name, float pitch, float rough, float robot, float echo, boolean radio, float gainDb) {
            this(name, pitch, rough, robot, echo, radio, gainDb, false);
        }

        Preset(String name, float pitch, float rough, float robot, float echo, boolean radio, float gainDb,
               boolean custom) {
            this.name = name;
            this.custom = custom;
            this.pitch = pitch;
            this.rough = rough;
            this.robot = robot;
            this.echo = echo;
            this.radio = radio;
            this.gainDb = gainDb;
        }
    }

    static final Preset[] PRESETS = {
            new Preset("Без эффекта", 0, 0, 0, 0, false, 0),
            new Preset("Грубый", 0, 0.55f, 0, 0, false, 0),
            new Preset("Очень грубый", -2, 1f, 0, 0, false, 0),
            new Preset("Басистый", -4, 0.2f, 0, 0, false, 2),
            new Preset("Монстр", -5, 0.8f, 0, 0.15f, false, 0),
            new Preset("Женский", 2, -0.5f, 0, 0, false, 0),
            new Preset("Ребёнок", 4, -0.6f, 0, 0, false, 0),
            new Preset("Бурундук", 7, -0.2f, 0, 0, false, 0),
            new Preset("Робот", 0, 0, 0.8f, 0, false, 0),
            new Preset("Пришелец", 4, 0, 0.5f, 0.2f, false, 0),
            new Preset("Рация", 0, 0.2f, 0, 0, true, 0),
            new Preset("Пещера", -1, 0.2f, 0, 0.6f, false, 0),
    };

    void apply(Preset p) {
        pitch = p.pitch;
        rough = p.rough;
        robot = p.robot;
        echo = p.echo;
        radio = p.radio;
        gainDb = p.gainDb;
    }

    /** Пользовательский пресет с текущими параметрами. */
    Preset toPreset(String name) {
        return new Preset(name, pitch, rough, robot, echo, radio, gainDb, true);
    }

    /** Первый пресет из списка, с которым совпадают текущие параметры, или null. */
    Preset matchingPreset(List<Preset> presets) {
        for (Preset p : presets) {
            if (near(p.pitch, pitch) && near(p.rough, rough) && near(p.robot, robot) && near(p.echo, echo)
                    && p.radio == radio && near(p.gainDb, gainDb)) return p;
        }
        return null;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.006f;
    }

    /** Формат: "1 enabled pitch rough robot echo radio gain" (короче PROP_VALUE_MAX = 92). */
    String serialize() {
        return String.format(Locale.US, "1 %d %.2f %.3f %.3f %.3f %d %.1f",
                enabled ? 1 : 0, pitch, rough, robot, echo, radio ? 1 : 0, gainDb);
    }

    void load(SharedPreferences sp) {
        enabled = sp.getBoolean("enabled", true);
        pitch = sp.getFloat("pitch", 0);
        rough = sp.getFloat("rough", 0.55f);
        robot = sp.getFloat("robot", 0);
        echo = sp.getFloat("echo", 0);
        radio = sp.getBoolean("radio", false);
        gainDb = sp.getFloat("gain", 0);
    }

    void save(SharedPreferences sp) {
        sp.edit().putBoolean("enabled", enabled).putFloat("pitch", pitch).putFloat("rough", rough)
                .putFloat("robot", robot).putFloat("echo", echo).putBoolean("radio", radio)
                .putFloat("gain", gainDb).apply();
    }
}
