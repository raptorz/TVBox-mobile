package com.fongmi.android.tv.offline;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.Keep;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Immutable source snapshot stored in both Room and DownloadRequest.data. */
@Keep
public class OfflineVideo {
    private static final Gson JSON = new Gson();
    public String id = "", title = "", cover = "", episode = "", line = "", quality = "";
    public String config = "", site = "", vod = "", episodeUrl = "", url = "", mime;
    public Map<String, String> headers = new HashMap<>();
    public int videoHeight;
    public long created = System.currentTimeMillis();

    public void identify() { id = OfflineIdentity.id(config, site, vod, line, episodeUrl, quality); }
    public byte[] data() { return JSON.toJson(this).getBytes(StandardCharsets.UTF_8); }
    public static OfflineVideo from(byte[] data) { return JSON.fromJson(new String(data, StandardCharsets.UTF_8), OfflineVideo.class); }
    public Record record(String error) {
        Record record = new Record();
        record.id = id;
        record.json = JSON.toJson(this);
        record.created = created;
        record.error = error;
        return record;
    }

    @Entity(tableName = "offline_video")
    public static class Record {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String json = "";
        @NonNull public String error = "";
        public long created;
        public long position;
        public OfflineVideo video() { return JSON.fromJson(json, OfflineVideo.class); }
    }
}
