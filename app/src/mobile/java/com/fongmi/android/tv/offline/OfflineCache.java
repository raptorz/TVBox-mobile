package com.fongmi.android.tv.offline;

import android.content.Context;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import java.util.HashMap;

final class OfflineCache {
    static CacheDataSource.Factory factory(Context context, SimpleCache cache, OfflineVideo video, boolean readOnly, boolean offlineOnly) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Util.getUserAgent(context, context.getPackageName()))
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(new HashMap<>(video.headers));
        String taskId = video.id;
        CacheDataSource.Factory factory = new CacheDataSource.Factory().setCache(cache)
                .setCacheKeyFactory(spec -> OfflineIdentity.cacheKey(taskId, spec.uri.toString()))
                .setUpstreamDataSourceFactory(offlineOnly ? null : http);
        if (readOnly) factory.setCacheWriteDataSinkFactory(null);
        return factory;
    }
    static void remove(SimpleCache cache, String taskId) {
        for (String key : cache.getKeys()) if (key.startsWith(taskId + ":")) cache.removeResource(key);
    }
}
