package com.fongmi.android.tv.offline;

import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import java.util.List;

@Database(entities = {OfflineVideo.Record.class}, version = 1, exportSchema = true)
public abstract class OfflineDatabase extends RoomDatabase {
    public abstract Videos videos();
    @Dao public interface Videos {
        @Query("SELECT * FROM offline_video ORDER BY created DESC") List<OfflineVideo.Record> all();
        @Insert(onConflict = OnConflictStrategy.IGNORE) void insert(OfflineVideo.Record video);
        @Query("UPDATE offline_video SET error = :error WHERE id = :id") void error(String id, String error);
        @Query("UPDATE offline_video SET position = :position WHERE id = :id") void position(String id, long position);
        @Query("DELETE FROM offline_video WHERE id = :id") void delete(String id);
    }
}
