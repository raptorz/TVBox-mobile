package com.fongmi.android.tv.offline;

import android.os.Bundle;
import android.view.WindowManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.ActivityOfflinePlayerBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseActivity;

/** No source/detail lookup is needed to play completed downloads, including without a config. */
public class OfflinePlayerActivity extends BaseActivity {
    private ActivityOfflinePlayerBinding binding;
    private ExoPlayer player;
    private boolean started;
    private boolean opening;
    private int generation;
    @Override protected ViewBinding getBinding() { return binding = ActivityOfflinePlayerBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle savedInstanceState) {
        OfflineRepository.get().entries().observe(this, entries -> open());
        binding.title.setOnClickListener(v -> finish());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            var bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
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
