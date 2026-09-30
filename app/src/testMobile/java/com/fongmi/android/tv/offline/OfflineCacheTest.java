package com.fongmi.android.tv.offline;

import static org.junit.Assert.*;

import android.app.Application;
import android.net.Uri;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadProgress;
import androidx.media3.exoplayer.offline.DownloadRequest;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OfflineCacheTest {
    private SimpleCache cache;
    private StandaloneDatabaseProvider database;
    private File directory;
    private java.net.ServerSocket server;
    private Thread serverThread;
    private OfflineVideo video;
    private final byte[] content = new byte[512 * 1024];
    private final java.util.Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong transferred = new AtomicLong();
    private final AtomicLong requestedStart = new AtomicLong(-1);

    @Before public void setup() throws Exception {
        new java.util.Random(42).nextBytes(content);
        directory = java.nio.file.Files.createTempDirectory("offline-cache-test").toFile();
        database = new StandaloneDatabaseProvider(RuntimeEnvironment.getApplication());
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
        server = new java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket socket = server.accept()) {
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String requestLine = input.readLine();
                    String path = java.net.URI.create(requestLine.split(" ")[1]).getPath();
                    byte[] body = files.getOrDefault(path, content);
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    var output = socket.getOutputStream();
                    if (!"per-task-token".equals(headers.get("authorization"))) {
                        output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                        continue;
                    }
                    String range = headers.get("range");
                    int start = 0, end = body.length - 1;
                    String contentRange = "";
                    if (range != null) {
                        String[] values = range.substring(6).split("-", -1);
                        start = Integer.parseInt(values[0]);
                        if (!values[1].isEmpty()) end = Integer.parseInt(values[1]);
                        contentRange = "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n";
                    }
                    requestedStart.set(start);
                    int size = end - start + 1;
                    transferred.addAndGet(size);
                    output.write(("HTTP/1.1 " + (range == null ? "200 OK" : "206 Partial Content") + "\r\nContent-Length: " + size + "\r\n" + contentRange + "Connection: close\r\n\r\n").getBytes());
                    output.write(body, start, size);
                } catch (IOException e) { if (!server.isClosed()) throw new RuntimeException(e); }
            }
        });
        serverThread.start();
        video = new OfflineVideo();
        video.id = "task-one";
        video.url = "http://127.0.0.1:" + server.getLocalPort() + "/video.mp4?signature=keep-me";
        video.headers.put("Authorization", "per-task-token");
    }
    @After public void cleanup() throws Exception {
        server.close();
        serverThread.join(2000);
        cache.release();
        SimpleCache.delete(directory, database);
        database.close();
    }
    private androidx.media3.datasource.cache.CacheDataSource.Factory factory(boolean readOnly, boolean offline) {
        return OfflineCache.factory(RuntimeEnvironment.getApplication(), cache, video, readOnly, offline);
    }
    private DataSpec spec(long position, long length) { return new DataSpec.Builder().setUri(video.url).setPosition(position).setLength(length).build(); }
    private byte[] read(DataSource source, DataSpec spec) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            source.open(spec);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, count);
        } finally { source.close(); }
        return out.toByteArray();
    }
    @Test public void resumesRangesAfterCacheRecreationAndPlaysWithoutServer() throws Exception {
        new CacheWriter(factory(false, false).createDataSource(), spec(0, 128 * 1024), null, null).cache();
        long before = cache.getCacheSpace();
        assertEquals(128 * 1024, before);
        cache.release();
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
        new CacheWriter(factory(false, false).createDataSource(), spec(0, content.length), null, null).cache();
        assertEquals(before, requestedStart.get());
        assertEquals(content.length, transferred.get());
        server.close();
        assertArrayEquals(content, read(factory(true, true).createDataSource(), spec(0, content.length)));
    }
    @Test public void partialPlaybackDoesNotWriteOrResumeDownload() throws Exception {
        new CacheWriter(factory(false, false).createDataSource(), spec(0, 65536), null, null).cache();
        long before = cache.getCacheSpace();
        assertArrayEquals(content, read(factory(true, false).createDataSource(), spec(0, content.length)));
        assertEquals(before, cache.getCacheSpace());
        assertArrayEquals(Arrays.copyOf(content, 65536), read(factory(true, true).createDataSource(), spec(0, 65536)));
        assertThrows(IOException.class, () -> read(factory(true, true).createDataSource(), spec(65536, 65536)));
    }
    @Test public void credentialsAreSnapshotsAndTasksCannotReadEachOthersBytes() throws Exception {
        var first = factory(false, false);
        video.headers.put("Authorization", "changed-after-factory");
        new CacheWriter(first.createDataSource(), spec(0, 65536), null, null).cache();
        video.id = "another-episode";
        assertThrows(IOException.class, () -> read(factory(true, true).createDataSource(), spec(0, 65536)));
        video.id = "task-one";
        assertEquals(65536, read(factory(true, true).createDataSource(), spec(0, 65536)).length);
    }
    @Test public void manualPauseAndRequestMetadataSurviveIndexRecreation() throws Exception {
        DownloadRequest request = new DownloadRequest.Builder(video.id, Uri.parse(video.url)).setData(video.data()).build();
        DownloadProgress progress = new DownloadProgress();
        progress.bytesDownloaded = 12345;
        progress.percentDownloaded = 12;
        new DefaultDownloadIndex(database, "test").putDownload(new Download(request, Download.STATE_STOPPED, 1, 2, content.length, OfflineRepository.STOP_USER, Download.FAILURE_REASON_NONE, progress));
        Download loaded = new DefaultDownloadIndex(database, "test").getDownload(video.id);
        assertNotNull(loaded);
        assertEquals(OfflineRepository.STOP_USER, loaded.stopReason);
        assertEquals(12345, loaded.getBytesDownloaded());
        assertEquals(video.headers, OfflineVideo.from(loaded.request.data).headers);
    }
    @Test public void cancelledWriterKeepsPartialBytesAndCanResume() throws Exception {
        CacheWriter[] writer = new CacheWriter[1];
        writer[0] = new CacheWriter(factory(false, false).createDataSource(), spec(0, content.length), new byte[8192], (length, cached, added) -> {
            if (cached >= 65536) writer[0].cancel();
        });
        assertThrows(java.io.InterruptedIOException.class, () -> writer[0].cache());
        long saved = cache.getCacheSpace();
        assertTrue(saved >= 65536 && saved < content.length);
        new CacheWriter(factory(false, false).createDataSource(), spec(0, content.length), null, null).cache();
        assertEquals(saved, requestedStart.get());
        assertArrayEquals(content, read(factory(true, true).createDataSource(), spec(0, content.length)));
    }
    @Test public void hlsPlaylistInitializationKeyAndSegmentsAreAvailableOffline() throws Exception {
        files.put("/vod.m3u8", ("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:2\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001\n"
                + "#EXTINF:2.0,\npart.m4s\n#EXT-X-ENDLIST\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("/init.mp4", new byte[64]);
        files.put("/key.bin", new byte[16]);
        files.put("/part.m4s", new byte[128]);
        checkAdaptiveOffline("vod.m3u8", "application/x-mpegURL", "init.mp4", "key.bin", "part.m4s");
    }
    @Test public void staticDashManifestInitializationAndSegmentsAreAvailableOffline() throws Exception {
        files.put("/vod.mpd", ("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT2S\" minBufferTime=\"PT1S\">"
                + "<Period duration=\"PT2S\"><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><Representation id=\"v\" bandwidth=\"100000\" width=\"320\" height=\"180\" codecs=\"avc1.42c01e\">"
                + "<SegmentList timescale=\"1\" duration=\"2\"><Initialization sourceURL=\"init.mp4\"/><SegmentURL media=\"part.m4s\"/></SegmentList>"
                + "</Representation></AdaptationSet></Period></MPD>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("/init.mp4", new byte[64]);
        files.put("/part.m4s", new byte[128]);
        checkAdaptiveOffline("vod.mpd", "application/dash+xml", "init.mp4", "part.m4s");
    }
    private void checkAdaptiveOffline(String manifest, String mime, String... resources) throws Exception {
        String root = "http://127.0.0.1:" + server.getLocalPort() + "/";
        video.url = root + manifest;
        DownloadRequest request = new DownloadRequest.Builder(video.id, Uri.parse(video.url)).setMimeType(mime).build();
        new androidx.media3.exoplayer.offline.DefaultDownloaderFactory(factory(false, false), Runnable::run).createDownloader(request).download(null);
        server.close();
        assertArrayEquals(files.get("/" + manifest), read(factory(true, true).createDataSource(), new DataSpec(Uri.parse(root + manifest))));
        for (String resource : resources) assertArrayEquals(files.get("/" + resource), read(factory(true, true).createDataSource(), new DataSpec(Uri.parse(root + resource))));
    }
    @Test public void deletionReleasesOnlyTheChosenTasksBytes() throws Exception {
        new CacheWriter(factory(false, false).createDataSource(), spec(0, 65536), null, null).cache();
        video.id = "task-two";
        new CacheWriter(factory(false, false).createDataSource(), spec(0, 65536), null, null).cache();
        assertEquals(131072, cache.getCacheSpace());
        OfflineCache.remove(cache, "task-one");
        assertEquals(65536, cache.getCacheSpace());
        assertEquals(65536, read(factory(true, true).createDataSource(), spec(0, 65536)).length);
        video.id = "task-one";
        assertThrows(IOException.class, () -> read(factory(true, true).createDataSource(), spec(0, 65536)));
    }
    @Test public void identityIncludesEpisodeLineAndQualityWithoutDelimiterCollisions() {
        assertNotEquals(OfflineIdentity.id("ab", "c"), OfflineIdentity.id("a", "bc"));
        assertNotEquals(OfflineIdentity.id("a:b", "c"), OfflineIdentity.id("a", "b:c"));
        video.config = "source"; video.site = "site"; video.vod = "vod"; video.line = "line"; video.episodeUrl = "episode"; video.quality = "HD"; video.identify();
        String id = video.id;
        video.url = "https://example.org/new-token"; video.identify(); assertEquals(id, video.id);
        video.episodeUrl = "episode2"; video.identify(); assertNotEquals(id, video.id);
        video.episodeUrl = "episode"; video.quality = "4K"; video.identify(); assertNotEquals(id, video.id);
        video.quality = "HD"; video.line = "other-line"; video.identify(); assertNotEquals(id, video.id);
    }
}
