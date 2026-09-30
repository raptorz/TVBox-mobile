package com.fongmi.android.tv.offline;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Scheduler;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import java.util.List;

public final class OfflineDownloadService extends DownloadService {
    private static final String CHANNEL = "offline_downloads";
    public OfflineDownloadService() { super(4101, 1000, CHANNEL, R.string.offline_title, 0); }
    @Override protected DownloadManager getDownloadManager() { return OfflineRepository.get().manager(); }
    @Override protected Scheduler getScheduler() { return new PlatformScheduler(this, 4102); }
    @Override protected Notification getForegroundNotification(List<Download> downloads, int notMetRequirements) {
        Intent intent = new Intent(this, HomeActivity.class).putExtra("offline", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 4101, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new DownloadNotificationHelper(this, CHANNEL).buildProgressNotification(this, R.drawable.ic_nav_cache, pending, "视频缓存", downloads, notMetRequirements);
    }
    @Override public void onTimeout(int startId, int fgsType) {
        DownloadManager manager = OfflineRepository.get().manager();
        for (Download download : manager.getCurrentDownloads()) if (download.stopReason == 0) manager.setStopReason(download.request.id, OfflineRepository.STOP_TIMEOUT);
        super.onTimeout(startId, fgsType);
    }
}
