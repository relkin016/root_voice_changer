package ru.relkin.rootvoice;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Список записей в папке приложения: воспроизведение, «Поделиться», удаление. */
final class RecordingsPanel {
    private final Activity activity;
    private final ViewGroup list;
    private final TextView empty;
    private final File dir;
    private MediaPlayer player;
    private File playing;

    RecordingsPanel(Activity activity, ViewGroup list, TextView empty) {
        this.activity = activity;
        this.list = list;
        this.empty = empty;
        File ext = activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        dir = ext != null ? ext : new File(activity.getFilesDir(), "recordings");
        dir.mkdirs();
    }

    File newFile() {
        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return new File(dir, "RootVoice_" + name + ".wav");
    }

    void refresh() {
        list.removeAllViews();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".wav"));
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        empty.setVisibility(files.length == 0 ? View.VISIBLE : View.GONE);
        LayoutInflater inf = activity.getLayoutInflater();
        for (File f : files) {
            View row = inf.inflate(R.layout.item_recording, list, false);
            ((TextView) row.findViewById(R.id.title)).setText(f.getName().replace("RootVoice_", "").replace(".wav", "")
                    .replace('_', ' '));
            long ms = WavWriter.durationMs(f, VoiceEngine.SR, 1);
            ((TextView) row.findViewById(R.id.subtitle)).setText(formatDuration(ms) + " · "
                    + Formatter.formatShortFileSize(activity, f.length()));
            MaterialButton play = row.findViewById(R.id.btnPlay);
            play.setIconResource(f.equals(playing) ? R.drawable.ic_stop : R.drawable.ic_play);
            play.setOnClickListener(v -> togglePlay(f));
            row.findViewById(R.id.btnShare).setOnClickListener(v -> share(f));
            row.findViewById(R.id.btnDelete).setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
                    .setTitle("Удалить запись?")
                    .setMessage(f.getName())
                    .setPositiveButton("Удалить", (d, w) -> {
                        if (f.equals(playing)) stopPlayback();
                        f.delete();
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            list.addView(row);
        }
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void togglePlay(File f) {
        boolean same = f.equals(playing);
        stopPlayback();
        if (!same) {
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnCompletionListener(p -> {
                    stopPlayback();
                    refresh();
                });
                mp.prepare();
                mp.start();
                player = mp;
                playing = f;
            } catch (IOException | RuntimeException e) {
                mp.release();
                new MaterialAlertDialogBuilder(activity).setTitle("Не удалось воспроизвести")
                        .setMessage(String.valueOf(e.getMessage())).setPositiveButton(android.R.string.ok, null).show();
            }
        }
        refresh();
    }

    void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
        }
        player = null;
        playing = null;
    }

    private void share(File f) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", f);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "Поделиться записью"));
    }
}
