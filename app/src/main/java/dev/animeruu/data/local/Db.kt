package dev.animeruu.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val animeId: String,
    val title: String,
    val posterUrl: String?,
    val coverUrl: String?,
    val kind: String,
    val genres: String,
    val year: String?,
    val addedAt: Long,
)

/** Satu baris per anime: episode terakhir yang ditonton → sumber "Lanjutkan menonton". */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val animeId: String,
    val animeTitle: String,
    val posterUrl: String?,
    val coverUrl: String?,
    val episodeId: String,
    val episodeLabel: String,
    val episodeTitle: String,
    val episodeThumb: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val isMovie: Boolean,
)

/** Posisi per episode — untuk resume dan tanda "sudah ditonton" di daftar episode. */
@Entity(tableName = "episode_progress")
data class EpisodeProgressEntity(
    @PrimaryKey val episodeId: String,
    val animeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Cache hasil /api/poster (Kitsu) per judul; notFound juga dicache agar tidak dipanggil ulang. */
@Entity(tableName = "poster_cache")
data class PosterCacheEntity(
    @PrimaryKey val key: String,
    val poster: String?,
    val cover: String?,
    val rating: Double?,
    val synopsis: String?,
    val exact: Boolean,
    val episodes: Int?,
    val notFound: Boolean,
    val fetchedAt: Long,
)

/** Metadata unduhan; status byte-nya dikelola Media3 DownloadManager. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val animeId: String,
    val animeTitle: String,
    val episodeLabel: String,
    val episodeTitle: String,
    val posterUrl: String?,
    val url: String,
    val quality: String,
    val sizeMb: Double?,
    val isMovie: Boolean,
    val createdAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE animeId = :id)")
    fun observeIsFavorite(id: String): Flow<Boolean>

    @Upsert suspend fun upsert(e: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE animeId = :id")
    suspend fun delete(id: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE animeId = :animeId")
    fun observe(animeId: String): Flow<HistoryEntity?>

    @Upsert suspend fun upsert(e: HistoryEntity)

    @Query("DELETE FROM history WHERE animeId = :animeId")
    suspend fun delete(animeId: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Upsert suspend fun upsertProgress(e: EpisodeProgressEntity)

    @Query("SELECT * FROM episode_progress WHERE episodeId = :episodeId")
    suspend fun progress(episodeId: String): EpisodeProgressEntity?

    @Query("SELECT * FROM episode_progress WHERE animeId = :animeId")
    fun observeProgressFor(animeId: String): Flow<List<EpisodeProgressEntity>>

    @Query("DELETE FROM episode_progress")
    suspend fun clearProgress()
}

@Dao
interface PosterDao {
    @Query("SELECT * FROM poster_cache WHERE `key` = :key")
    suspend fun get(key: String): PosterCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: PosterCacheEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE episodeId = :id")
    suspend fun get(id: String): DownloadEntity?

    @Upsert suspend fun upsert(e: DownloadEntity)

    @Query("DELETE FROM downloads WHERE episodeId = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class, EpisodeProgressEntity::class, PosterCacheEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun posters(): PosterDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "animeruu.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
