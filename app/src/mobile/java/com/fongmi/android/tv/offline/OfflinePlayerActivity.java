package com.fongmi.android.tv.offline;

import android.os.Bundle;
import android.view.WindowManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.ActivityOfflinePlayerBinding;
import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseActivity;

/** No source/detail lookup is needed to play completed downloads, including without a config. */
public class OfflinePlayerActivity extends BaseActivity {
    private static final String SPEED_PREFERENCE = "offline_playback_speed";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
    private static final String[] SPEED_LABELS = {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"};
    private int speedIndex = 2;
    private ActivityOfflinePlayerBinding binding;
    private ExoPlayer player;
    private boolean started;
    private boolean opening;
    private int generation;
    @Override protected ViewBinding getBinding() { return binding = ActivityOfflinePlayerBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle savedInstanceState) {
        float savedSpeed = Prefers.getFloat(SPEED_PREFERENCE, 1f);
        for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] == savedSpeed) speedIndex = i;
        updateSpeedButton();
        binding.speed.setOnClickListener(v -> showSpeedPicker());
        binding.player.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            // FIT expands to the entire inset-safe screen when controls are hidden.
            binding.title.setVisibility(visibility);
            binding.footer.setVisibility(visibility);
        });
        OfflineRepository.get().entries().observe(this, entries -> open());
        binding.title.setOnClickListener(v -> finish());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            var bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
    private void updateSpeedButton() {
        binding.speed.setText(getString(R.string.play_speed_hint, SPEED_LABELS[speedIndex]));
    }
    private void showSpeedPicker() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.play_speed)
                .setSingleChoiceItems(SPEED_LABELS, speedIndex, (dialog, which) -> {
                    speedIndex = which;
                    Prefers.put(SPEED_PREFERENCE, SPEEDS[which]);
                    if (player != null) player.setPlaybackSpeed(SPEEDS[which]);
                    updateSpeedButton();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    @Override protected void onStart() { super.onStart(); started = true; open(); }
    private void open() {
        if (!started || player != null || opening) return;
        OfflineRepository.Entry entry = OfflineRepository.get().find(getIntent().getStringExtra("id"));
        if (entry == null || entry.download() == null || entry.removing()) return;
        opening = true;
        int expected = generation;
        OfflineRepository.get().preparePlayback(entry, () -> {
            if (!started || expected != generation) return;
            opening = false;
            createPlayer(entry);
        });
    }
    private void createPlayer(OfflineRepository.Entry entry) {
        if (Server.get().getService() != null) Server.get().getService().suspend();
        binding.title.setText("‹  " + entry.video().title + " · " + entry.video().episode);
        binding.message.setText(entry.complete() ? "离线播放" : "部分缓存：缺失内容需要联网；播放不会恢复已暂停的缓存任务。");
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(OfflineRepository.get().dataSource(entry.video(), true, entry.complete())))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true).build();
        player.setPlaybackSpeed(SPEEDS[speedIndex]);
        binding.player.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { binding.message.setText(entry.complete() ? "缓存数据无法播放，请检查文件是否被清理或重新缓存。" : "缺少可播放的数据或播放地址已过期，请联网后继续缓存，或打开原视频更新地址。"); }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        player.setMediaItem(entry.download().request.toMediaItem(), Math.max(0, OfflineRepository.get().position(entry)));
        player.prepare();
        player.play();
    }
    @Override protected void onStop() {
        started = false;
        opening = false;
        generation++;
        if (player != null) {
            OfflineRepository.get().savePosition(getIntent().getStringExtra("id"), player.getCurrentPosition());
            binding.player.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }
}
