package com.fongmi.android.tv.offline;

import android.net.Uri;
import androidx.media3.datasource.HttpDataSource;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.server.Server;
import java.io.IOException;

/** Never attach old bytes to a different signed URL or silently switch the application's source. */
final class OfflineSource {
    static boolean local(String url) {
        String host = Uri.parse(url).getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }
    static void prepareProxy(OfflineVideo video) throws IOException {
        if (!local(video.url)) return;
        if (!video.config.equals(VodConfig.getUrl())) throw new IOException("请先加载原站源，再继续此代理视频的缓存");
        try {
            VodConfig.get().ensureLoaded();
            Server.get().start();
            Result result = SiteApi.playerContent(video.site, video.line, video.episodeUrl);
            selectQuality(result, video);
            if (!video.url.equals(result.getRealUrl())) throw new IOException("代理地址已变化，请从播放页重新缓存；原缓存仍保留");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("代理尚未就绪，请打开原视频后继续缓存", e); }
    }
    private static void selectQuality(Result result, OfflineVideo video) {
        for (int i = 0; i < result.getUrl().getValues().size(); i++) {
            if (video.quality.equals(result.getUrl().n(i))) { result.getUrl().set(i); return; }
        }
    }
    static boolean expired(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException http && (http.responseCode == 401 || http.responseCode == 403 || http.responseCode == 410)) return true;
        }
        return false;
    }
    static void refresh(OfflineVideo video) throws IOException, InterruptedException {
        if (!video.config.equals(VodConfig.getUrl())) throw new IOException("请先加载原站源，再更新播放地址");
        try {
            VodConfig.get().ensureLoaded();
            Result result = SiteApi.playerContent(video.site, video.line, video.episodeUrl);
            selectQuality(result, video);
            String url = result.getRealUrl();
            java.util.Map<String, String> headers = result.getHeader();
            if (result.isUseParse()) {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                String[] resolved = new String[1];
                java.util.Map<String, String> parsedHeaders = new java.util.HashMap<>();
                com.fongmi.android.tv.player.parse.ParseJob job = com.fongmi.android.tv.player.parse.ParseJob.create(new com.fongmi.android.tv.impl.ParseCallback() {
                    @Override public void onParseSuccess(java.util.Map<String, String> value, String uri, String from) { parsedHeaders.putAll(value); resolved[0] = uri; latch.countDown(); }
                    @Override public void onParseError() { latch.countDown(); }
                }).start(result, result.isUseParse());
                try {
                    if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS) || resolved[0] == null) throw new IOException("地址解析失败，请打开原视频重试");
                    url = resolved[0]; headers = parsedHeaders;
                } finally { job.stop(); }
            }
            if (!video.url.equals(url)) throw new IOException("播放地址已变化，请在原播放页确认重新缓存；原缓存仍保留");
            video.headers = new java.util.HashMap<>(headers);
        } catch (InterruptedException | IOException e) { throw e; }
        catch (Exception e) { throw new IOException("更新播放地址失败，请打开原视频重试", e); }
    }
    static String error(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException http && (http.responseCode == 401 || http.responseCode == 403 || http.responseCode == 410))
                return "播放地址已过期，请打开原视频更新地址；原缓存仍保留";
            if (cause instanceof androidx.media3.exoplayer.offline.DownloadHelper.LiveContentUnsupportedException)
                return "直播流不支持缓存";
        }
        // HTTP exceptions can contain signed URLs and headers: do not display raw network errors.
        if (exception.getClass() == IOException.class && exception.getMessage() != null && exception.getMessage().matches(".*[\\p{IsHan}].*")) return exception.getMessage();
        return "缓存失败，请检查网络后重试";
    }
}
