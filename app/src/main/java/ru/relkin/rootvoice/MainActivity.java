package ru.relkin.rootvoice;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_MIC = 1;
    private static final long PUSH_DELAY_MS = 120;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceParams params = new VoiceParams();
    private final Preview preview = new Preview();
    private final Runnable push = this::pushParams;

    private SharedPreferences prefs;
    private boolean rootOk;
    private ModuleInstaller.State state;
    private boolean effectLoaded;
    private boolean busy;
    private boolean updatingUi;

    private TextView status, lblRough, lblPitch, lblRobot, lblEcho, lblGain, previewInfo;
    private View progress, advanced;
    private MaterialButton btnInstall, btnRemove, btnReboot, btnListen;
    private MaterialSwitch swEnabled, swRadio;
    private ChipGroup presets;
    private Slider sRough, sPitch, sRobot, sEcho, sGain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        advanced = findViewById(R.id.advanced);
        previewInfo = findViewById(R.id.previewInfo);
        lblRough = findViewById(R.id.lblRough);
        lblPitch = findViewById(R.id.lblPitch);
        lblRobot = findViewById(R.id.lblRobot);
        lblEcho = findViewById(R.id.lblEcho);
        lblGain = findViewById(R.id.lblGain);
        btnInstall = findViewById(R.id.btnInstall);
        btnRemove = findViewById(R.id.btnRemove);
        btnReboot = findViewById(R.id.btnReboot);
        btnListen = findViewById(R.id.btnListen);
        swEnabled = findViewById(R.id.swEnabled);
        swRadio = findViewById(R.id.swRadio);
        presets = findViewById(R.id.presets);
        sRough = findViewById(R.id.sRough);
        sPitch = findViewById(R.id.sPitch);
        sRobot = findViewById(R.id.sRobot);
        sEcho = findViewById(R.id.sEcho);
        sGain = findViewById(R.id.sGain);
        ((TextView) findViewById(R.id.version)).setText("Версия " + BuildConfigCompat.versionName(this));

        prefs = getSharedPreferences("voice", MODE_PRIVATE);
        params.load(prefs);

        for (int i = 0; i < VoiceParams.PRESETS.length; i++) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_preset, presets, false);
            chip.setId(View.generateViewId());
            chip.setTag(i);
            chip.setText(VoiceParams.PRESETS[i].name);
            presets.addView(chip);
        }
        presets.setOnCheckedStateChangeListener((group, ids) -> {
            if (updatingUi || ids.isEmpty()) return;
            params.apply(VoiceParams.PRESETS[(int) group.findViewById(ids.get(0)).getTag()]);
            showParams();
            onParamsChanged();
        });

        swEnabled.setOnCheckedChangeListener((b, checked) -> {
            if (updatingUi) return;
            params.enabled = checked;
            onParamsChanged();
        });
        swRadio.setOnCheckedChangeListener((b, checked) -> {
            if (updatingUi) return;
            params.radio = checked;
            onSliderChanged();
        });
        sRough.addOnChangeListener((s, v, user) -> {
            if (user) { params.rough = v / 100f; onSliderChanged(); }
        });
        sPitch.addOnChangeListener((s, v, user) -> {
            if (user) { params.pitch = v; onSliderChanged(); }
        });
        sRobot.addOnChangeListener((s, v, user) -> {
            if (user) { params.robot = v / 100f; onSliderChanged(); }
        });
        sEcho.addOnChangeListener((s, v, user) -> {
            if (user) { params.echo = v / 100f; onSliderChanged(); }
        });
        sGain.addOnChangeListener((s, v, user) -> {
            if (user) { params.gainDb = v; onSliderChanged(); }
        });

        findViewById(R.id.btnAdvanced).setOnClickListener(v ->
                advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        btnInstall.setOnClickListener(v -> install());
        btnRemove.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Удалить модуль?")
                .setMessage("Эффект исчезнет из системы после перезагрузки.")
                .setPositiveButton(R.string.remove, (d, w) -> runRoot(() -> {
                    ModuleInstaller.remove();
                    return null;
                }, r -> refreshStatus()))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        btnReboot.setOnClickListener(v -> confirmReboot("Перезагрузить телефон?"));
        btnListen.setOnClickListener(v -> toggleListen());

        showParams();
        updateStatusUi();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        effectLoaded = ModuleInstaller.effectLoaded();
        updateStatusUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopListen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---- параметры -----------------------------------------------------------------------

    private void showParams() {
        updatingUi = true;
        swEnabled.setChecked(params.enabled);
        swRadio.setChecked(params.radio);
        sRough.setValue(clampToSlider(sRough, Math.round(params.rough * 100)));
        sPitch.setValue(clampToSlider(sPitch, Math.round(params.pitch * 2) / 2f));
        sRobot.setValue(clampToSlider(sRobot, Math.round(params.robot * 100)));
        sEcho.setValue(clampToSlider(sEcho, Math.round(params.echo * 100)));
        sGain.setValue(clampToSlider(sGain, Math.round(params.gainDb)));
        int match = params.matchingPreset();
        presets.clearCheck();
        if (match >= 0) {
            for (int i = 0; i < presets.getChildCount(); i++) {
                View c = presets.getChildAt(i);
                if ((int) c.getTag() == match) presets.check(c.getId());
            }
        }
        updatingUi = false;
        updateLabels();
    }

    private static float clampToSlider(Slider s, float v) {
        return Math.max(s.getValueFrom(), Math.min(s.getValueTo(), v));
    }

    private void updateLabels() {
        int r = Math.round(params.rough * 100);
        lblRough.setText(getString(R.string.roughness) + ": " + (r > 0 ? "+" : "") + r);
        lblPitch.setText(String.format(Locale.US, "%s: %+.1f полутона", getString(R.string.pitch), params.pitch));
        lblRobot.setText(getString(R.string.robot) + ": " + Math.round(params.robot * 100) + "%");
        lblEcho.setText(getString(R.string.echo) + ": " + Math.round(params.echo * 100) + "%");
        lblGain.setText(String.format(Locale.US, "%s: %+d дБ", getString(R.string.gain), Math.round(params.gainDb)));
    }

    private void onSliderChanged() {
        // Ручная правка: подсвечиваем пресет, только если параметры с ним совпали.
        int match = params.matchingPreset();
        updatingUi = true;
        presets.clearCheck();
        for (int i = 0; match >= 0 && i < presets.getChildCount(); i++) {
            View c = presets.getChildAt(i);
            if ((int) c.getTag() == match) presets.check(c.getId());
        }
        updatingUi = false;
        updateLabels();
        onParamsChanged();
    }

    private void onParamsChanged() {
        params.save(prefs);
        preview.setParams(params.serialize());
        main.removeCallbacks(push);
        main.postDelayed(push, PUSH_DELAY_MS);
    }

    private void pushParams() {
        if (!rootOk) return;
        String p = params.serialize();
        io.execute(() -> {
            try {
                ModuleInstaller.applyParams(p);
            } catch (IOException e) {
                main.post(() -> {
                    rootOk = false;
                    updateStatusUi();
                });
            }
        });
    }

    // ---- состояние и модуль --------------------------------------------------------------

    private void refreshStatus() {
        String p = params.serialize();
        runRoot(() -> {
            rootOk = RootShell.available();
            if (!rootOk) return null;
            ModuleInstaller.applyParams(p);
            return ModuleInstaller.state();
        }, s -> {
            state = s;
            effectLoaded = ModuleInstaller.effectLoaded();
        });
    }

    private void install() {
        String p = params.serialize();
        runRoot(() -> ModuleInstaller.install(this, p), log -> {
            refreshStatus();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Модуль установлен")
                    .setMessage(log + "\n\nЧтобы аудиосистема загрузила эффект, нужна перезагрузка.")
                    .setPositiveButton(R.string.reboot, (d, w) -> runRoot(() -> {
                        ModuleInstaller.reboot();
                        return null;
                    }, r -> { }))
                    .setNegativeButton("Позже", null)
                    .show();
        });
    }

    private void confirmReboot(String title) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setPositiveButton(R.string.reboot, (d, w) -> runRoot(() -> {
                    ModuleInstaller.reboot();
                    return null;
                }, r -> { }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface RootTask<T> {
        T run() throws IOException;
    }

    private interface Done<T> {
        void accept(T result);
    }

    /** Выполняет задачу с root в фоне; ошибки показывает диалогом. */
    private <T> void runRoot(RootTask<T> task, Done<T> done) {
        busy = true;
        updateStatusUi();
        io.execute(() -> {
            T result = null;
            String error = null;
            try {
                result = task.run();
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            T r = result;
            String err = error;
            main.post(() -> {
                if (isDestroyed()) return;
                busy = false;
                if (err != null) {
                    new MaterialAlertDialogBuilder(this).setTitle("Ошибка").setMessage(err)
                            .setPositiveButton(android.R.string.ok, null).show();
                } else {
                    done.accept(r);
                }
                updateStatusUi();
            });
        });
    }

    private void updateStatusUi() {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        StringBuilder sb = new StringBuilder();
        if (busy && !rootOk && state == null) {
            status.setText("Проверяю root-доступ…");
            btnInstall.setEnabled(false);
            btnRemove.setEnabled(false);
            btnReboot.setEnabled(false);
            return;
        }
        sb.append(rootOk ? "✓ Root-доступ есть" : "✗ Нет root-доступа (нужен Magisk, KernelSU или APatch)");
        sb.append('\n');
        if (rootOk && state != null) {
            switch (state) {
                case NOT_INSTALLED: sb.append("✗ Модуль не установлен"); break;
                case PENDING_INSTALL: sb.append("⟳ Модуль установлен — перезагрузите телефон"); break;
                case INSTALLED: sb.append("✓ Модуль установлен"); break;
                case DISABLED: sb.append("✗ Модуль отключён в root-менеджере"); break;
                case PENDING_REMOVE: sb.append("⟳ Модуль будет удалён после перезагрузки"); break;
            }
            sb.append('\n');
        }
        if (effectLoaded) {
            sb.append("✓ Эффект работает в аудиосистеме — голос меняется во всех приложениях");
        } else {
            sb.append("✗ Эффект не загружен аудиосистемой");
            if (state == ModuleInstaller.State.INSTALLED) {
                sb.append("\nМодуль активен, но прошивка не подхватила эффект. Возможные причины: "
                        + "AIDL-HAL эффектов (Android 14+ на части устройств) или нестандартный путь audio_effects.xml.");
            }
        }
        status.setText(sb);

        btnInstall.setEnabled(rootOk && !busy);
        btnInstall.setText(state == null || state == ModuleInstaller.State.NOT_INSTALLED
                ? R.string.install : R.string.reinstall);
        btnRemove.setEnabled(rootOk && !busy && state != null
                && state != ModuleInstaller.State.NOT_INSTALLED && state != ModuleInstaller.State.PENDING_REMOVE);
        btnReboot.setEnabled(rootOk && !busy);
    }

    // ---- прослушка -----------------------------------------------------------------------

    private void toggleListen() {
        if (preview.isRunning()) {
            stopListen();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListen();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListen();
        }
    }

    private void startListen() {
        effectLoaded = ModuleInstaller.effectLoaded();
        boolean inApp = !effectLoaded;
        previewInfo.setText(inApp
                ? "Модуль не активен: звук обрабатывается внутри приложения. Другие приложения пока слышат обычный голос."
                : "Звук обрабатывает системный модуль — так же вас слышат другие приложения.");
        btnListen.setText(R.string.stop_listen);
        preview.start(inApp, params.serialize(), error -> main.post(() -> {
            btnListen.setText(R.string.listen);
            if (error != null && !isDestroyed()) previewInfo.setText("Ошибка: " + error);
        }));
    }

    private void stopListen() {
        preview.stop();
        btnListen.setText(R.string.listen);
        previewInfo.setText(R.string.headphones);
    }
}
