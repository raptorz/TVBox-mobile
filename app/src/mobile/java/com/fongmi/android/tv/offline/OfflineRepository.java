package com.fongmi.android.tv.offline;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.Util;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.*;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.room.Room;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Notify;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns a permanent cache, separate from Exo's evictable playback/preload cache. */
public final class OfflineRepository {
    public static final int STOP_USER = 1, STOP_STORAGE = 2, STOP_TIMEOUT = 3;
    private static OfflineRepository instance;
    public static synchronized OfflineRepository get() {
        if (instance == null) instance = new OfflineRepository(App.get());
        return instance;
    }
    public static boolean keepSourceAlive() {
        return instance != null && (!instance.preparing.isEmpty() || !instance.manager.getCurrentDownloads().isEmpty());
    }

    public record Entry(OfflineVideo video, Download download, String error, long position, boolean preparing) {
        public boolean complete() { return download != null && download.state == Download.STATE_COMPLETED; }
        public boolean removing() { return download != null && (download.state == Download.STATE_REMOVING || download.state == Download.STATE_RESTARTING); }
        public boolean active() { return preparing || download != null && download.stopReason == 0 && (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED); }
        public String action() {
            if (complete()) return "已缓存";
            if (removing()) return "删除中";
            if (preparing) return "准备中";
            if (active()) return download.state == Download.STATE_DOWNLOADING ? "缓存中" : "排队中";
            return download != null && download.state == Download.STATE_FAILED ? "重试缓存" : "继续缓存";
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newFixedThreadPool(3);
    private final OfflineDatabase database;
    private final File directory;
    private final SimpleCache cache;
    private final DownloadManager manager;
    private final Map<String, Long> positions = new HashMap<>();
    private final Map<String, DownloadHelper> preparing = new HashMap<>();
    private final Map<String, OfflineVideo> replacements = new HashMap<>();
    private final Set<String> starting = new HashSet<>();
    private boolean refreshPending;
    private final MutableLiveData<List<Entry>> entries = new MutableLiveData<>(List.of()) {
        @Override protected void onActive() { refresh(); main.post(tick); }
        @Override protected void onInactive() { main.removeCallbacks(tick); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); if (entries.hasActiveObservers()) main.postDelayed(this, 1000); }
    };

    private OfflineRepository(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "offline-media");
        StandaloneDatabaseProvider provider = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), provider);
        database = Room.databaseBuilder(context, OfflineDatabase.class, "offline-videos.db").build();
        manager = new DownloadManager(context, new DefaultDownloadIndex(provider, "mobile_offline"), request -> {
            OfflineVideo video = OfflineVideo.from(request.data);
            return new Downloader() {
                private volatile Downloader delegate = create();
                private volatile boolean cancelled;
                private final java.util.concurrent.atomic.AtomicBoolean storageStopped = new java.util.concurrent.atomic.AtomicBoolean();
                private Downloader create() { return new DefaultDownloaderFactory(dataSource(video, false, false), downloads).createDownloader(request); }
                @Override public void download(ProgressListener listener) throws IOException, InterruptedException {
                    OfflineSource.prepareProxy(video);
                    if (cancelled) throw new InterruptedException();
                    try { downloadOnce(listener); }
                    catch (IOException e) {
                        if (!OfflineSource.expired(e) || cancelled) throw e;
                        OfflineSource.refresh(video);
                        if (cancelled) throw new InterruptedException();
                        delegate = create();
                        if (cancelled) { delegate.cancel(); throw new InterruptedException(); }
                        downloadOnce(listener);
                    }
                }
                private void downloadOnce(ProgressListener listener) throws IOException, InterruptedException {
                    delegate.download((length, bytes, percent) -> {
                        if (directory.getUsableSpace() < 64L * 1024 * 1024 && storageStopped.compareAndSet(false, true)) {
                            main.post(() -> manager.setStopReason(video.id, STOP_STORAGE));
                            delegate.cancel();
                        }
                        if (listener != null) listener.onProgress(length, bytes, percent);
                    });
                }
                @Override public void cancel() { cancelled = true; delegate.cancel(); }
                @Override public void remove() {
                    // Remove by namespace, even if the manifest is missing or the URL has expired.
                    OfflineCache.remove(cache, video.id);
                }
            };
        });
        manager.setMaxParallelDownloads(1);
        manager.setMinRetryCount(2);
        manager.setRequirements(new Requirements(Requirements.NETWORK | Requirements.DEVICE_STORAGE_NOT_LOW));
        manager.addListener(new DownloadManager.Listener() {
            @Override public void onInitialized(DownloadManager manager) { refresh(); }
            @Override public void onDownloadChanged(DownloadManager manager, Download download, Exception error) {
                starting.remove(download.request.id);
                io.execute(() -> {
                    database.videos().insert(OfflineVideo.from(download.request.data).record(""));
                    database.videos().error(download.request.id, error == null ? "" : OfflineSource.error(error));
                    main.post(OfflineRepository.this::refresh);
                });
            }
            @Override public void onDownloadRemoved(DownloadManager manager, Download download) {
                positions.remove(download.request.id);
                io.execute(() -> {
                    database.videos().delete(download.request.id);
                    main.post(() -> {
                        OfflineVideo replacement = replacements.remove(download.request.id);
                        if (replacement != null) prepare(replacement);
                        refresh();
                    });
                });
            }
            @Override public void onRequirementsStateChanged(DownloadManager manager, Requirements requirements, int notMet) { refresh(); }
        });
        refresh();
    }

    public DownloadManager manager() { return manager; }
    public LiveData<List<Entry>> entries() { return entries; }
    public Entry find(String id) {
        for (Entry entry : entries.getValue()) if (entry.video.id.equals(id)) return entry;
        return null;
    }
    public CacheDataSource.Factory dataSource(OfflineVideo video, boolean readOnly, boolean offlineOnly) {
        return OfflineCache.factory(context, cache, video, readOnly, offlineOnly);
    }

    public void refresh() {
        if (refreshPending) return;
        refreshPending = true;
        Set<String> pending = new HashSet<>(preparing.keySet());
        pending.addAll(starting);
        Map<String, Download> current = new HashMap<>();
        for (Download download : manager.getCurrentDownloads()) current.put(download.request.id, download);
        io.execute(() -> {
            List<Entry> result = new ArrayList<>();
            try {
                try (DownloadCursor cursor = manager.getDownloadIndex().getDownloads()) {
                    while (cursor.moveToNext()) {
                        Download download = cursor.getDownload();
                        current.putIfAbsent(download.request.id, download);
                        database.videos().insert(OfflineVideo.from(download.request.data).record(""));
                    }
                }
                for (OfflineVideo.Record record : database.videos().all()) result.add(new Entry(record.video(), current.get(record.id), record.error, record.position, pending.contains(record.id)));
            } catch (Exception e) { android.util.Log.e("Offline", "Cannot read cache tasks", e); }
            main.post(() -> { refreshPending = false; entries.setValue(result); });
        });
    }
    public void toggle(OfflineVideo video) {
        Entry entry = find(video.id);
        if (starting.contains(video.id)) return;
        if (entry != null && (entry.complete() || entry.removing())) return;
        if (preparing.containsKey(video.id)) {
            preparing.remove(video.id).release();
            saveError(video.id, "已暂停");
        } else if (entry != null && entry.active()) {
            manager.setStopReason(video.id, STOP_USER);
        } else if (entry != null && entry.download != null) {
            if (!spaceAvailable()) return;
            try {
                // Failed downloads must be re-added, setting stopReason alone does not retry them.
                DownloadService.sendAddDownload(context, OfflineDownloadService.class, entry.download.request, 0, true);
            } catch (RuntimeException e) { Notify.show("无法启动后台缓存，请回到应用后重试"); }
        } else prepare(video);
        refresh();
    }
    private boolean spaceAvailable() {
        if (directory.getUsableSpace() >= 128L * 1024 * 1024) return true;
        Notify.show("存储空间不足，请释放空间后继续缓存");
        return false;
    }
    private void prepare(OfflineVideo video) {
        if (!spaceAvailable()) return;
        starting.add(video.id);
        io.execute(() -> {
            database.videos().insert(video.record(""));
            database.videos().error(video.id, "");
            main.post(() -> {
                starting.remove(video.id);
                try {
                    MediaItem item = new MediaItem.Builder().setUri(video.url).setMimeType(video.mime).build();
                    int type = Util.inferContentTypeForUriAndMimeType(Uri.parse(video.url), video.mime);
                    if (type != C.CONTENT_TYPE_OTHER && type != C.CONTENT_TYPE_HLS && type != C.CONTENT_TYPE_DASH) throw new IOException("仅支持 HTTP 视频、HLS 点播和静态 DASH");
                    DownloadHelper.Factory factory = new DownloadHelper.Factory();
                    if (type != C.CONTENT_TYPE_OTHER) {
                        TrackSelectionParameters.Builder parameters = DownloadHelper.getDefaultTrackSelectorParameters(context).buildUpon();
                        if (video.videoHeight > 0) parameters.setMaxVideoSize(Integer.MAX_VALUE, video.videoHeight);
                        factory.setDataSourceFactory(dataSource(video, true, false))
                                .setRenderersFactory(new DefaultRenderersFactory(context))
                                .setTrackSelectionParameters(parameters.build());
                    }
                    DownloadHelper helper = factory.create(item);
                    preparing.put(video.id, helper);
                    helper.prepare(new DownloadHelper.Callback() {
                        @Override public void onPrepared(DownloadHelper helper, boolean tracksAvailable) {
                            if (preparing.get(video.id) != helper) return;
                            try {
                                if (tracksAvailable) for (int p = 0; p < helper.getPeriodCount(); p++) {
                                    for (androidx.media3.common.Tracks.Group group : helper.getTracks(p).getGroups()) {
                                        for (int t = 0; t < group.length; t++) if (group.getTrackFormat(t).drmInitData != null) throw new IOException("暂不支持 DRM 视频缓存");
                                    }
                                }
                                DownloadRequest request = helper.getDownloadRequest(video.id, video.data());
                                DownloadService.sendAddDownload(context, OfflineDownloadService.class, request, true);
                                starting.add(video.id);
                            } catch (Exception e) { saveError(video.id, OfflineSource.error(e)); }
                            finally { preparing.remove(video.id); helper.release(); refresh(); }
                        }
                        @Override public void onPrepareError(DownloadHelper helper, IOException error) {
                            if (preparing.remove(video.id, helper)) { helper.release(); saveError(video.id, OfflineSource.error(error)); }
                        }
                    });
                    main.postDelayed(() -> {
                        if (preparing.remove(video.id, helper)) { helper.release(); saveError(video.id, "准备超时，请重试缓存"); }
                    }, 60000);
                } catch (Exception e) { saveError(video.id, OfflineSource.error(e)); }
                refresh();
            });
        });
    }
    private void saveError(String id, String error) {
        io.execute(() -> { database.videos().error(id, error); main.post(this::refresh); });
    }
    public void restart(Entry entry, OfflineVideo video) {
        if (entry.download == null) { remove(entry); prepare(video); }
        else { replacements.put(video.id, video); manager.removeDownload(video.id); }
    }
    public void remove(Entry entry) {
        DownloadHelper helper = preparing.remove(entry.video.id);
        if (helper != null) helper.release();
        if (entry.download != null) manager.removeDownload(entry.video.id);
        else io.execute(() -> { database.videos().delete(entry.video.id); main.post(this::refresh); });
        refresh();
    }
    public void preparePlayback(Entry entry, Runnable ready) {
        if (entry.complete() || !OfflineSource.local(entry.video.url)) { ready.run(); return; }
        downloads.execute(() -> {
            try { OfflineSource.prepareProxy(entry.video); }
            catch (IOException ignored) { /* Cached portions may still be playable without the proxy. */ }
            main.post(ready);
        });
    }
    public long position(Entry entry) { return positions.getOrDefault(entry.video.id, entry.position); }
    public void savePosition(String id, long position) {
        positions.put(id, position);
        io.execute(() -> database.videos().position(id, position));
    }
    public String status(Entry entry) {
        if (entry.download != null && entry.download.stopReason == STOP_STORAGE) return "空间不足，已暂停";
        if (entry.download != null && entry.download.stopReason == STOP_TIMEOUT) return "后台时限已到，点击继续缓存";
        if (entry.active() && manager.getNotMetRequirements() != 0) return "等待网络或存储空间";
        if (!entry.error.isEmpty()) return entry.error;
        return entry.action();
    }
}
