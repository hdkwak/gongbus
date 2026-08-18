package com.hidong.gongbus

import android.content.Context
import androidx.room.*
import com.google.gson.Gson

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: Int,
    val user_id: Int,
    val title: String?,
    val start_time: String,
    val distance_meters: Int?,
    val duration_seconds: Int?,
    val route_line_geojson: String?, // Store as String
    val username: String?,
    val avatar_url: String?,
    val avg_heart_rate: Int?,
    val avg_cadence: Int?,
    val total_calories: Int?,
    val like_count: Long,
    val comment_count: Long
) {
    fun toModel(gson: Gson): ActivityFeedItem {
        return ActivityFeedItem(
            id = id,
            user_id = user_id,
            title = title,
            start_time = start_time,
            distance_meters = distance_meters,
            duration_seconds = duration_seconds,
            route_line_geojson = route_line_geojson?.let { gson.fromJson(it, Any::class.java) },
            username = username,
            avatar_url = avatar_url,
            avg_heart_rate = avg_heart_rate,
            avg_cadence = avg_cadence,
            total_calories = total_calories,
            like_count = like_count,
            comment_count = comment_count
        )
    }
}

fun ActivityFeedItem.toEntity(gson: Gson): ActivityEntity {
    return ActivityEntity(
        id = id,
        user_id = user_id,
        title = title,
        start_time = start_time,
        distance_meters = distance_meters,
        duration_seconds = duration_seconds,
        route_line_geojson = gson.toJson(route_line_geojson),
        username = username,
        avatar_url = avatar_url,
        avg_heart_rate = avg_heart_rate,
        avg_cadence = avg_cadence,
        total_calories = total_calories,
        like_count = like_count,
        comment_count = comment_count
    )
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY start_time DESC")
    suspend fun getAll(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE user_id = :userId ORDER BY start_time DESC")
    suspend fun getByUserId(userId: Int): List<ActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<ActivityEntity>)

    @Query("DELETE FROM activities")
    suspend fun deleteAll()

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "activity_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
