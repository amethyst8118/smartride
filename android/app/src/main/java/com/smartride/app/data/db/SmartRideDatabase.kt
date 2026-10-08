package com.smartride.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A completed ride (report: "Store D2 -- SQLite Ride History"). */
@Entity(tableName = "rides", indices = [Index(value = ["sourceKey"], unique = true)])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "<source>/<startEpoch>/<distanceM>": unique per ride, stable across device reboots. */
    val sourceKey: String,
    val source: String,          // "device" | "demo"
    val sourceLabel: String,     // e.g. "SmartRide-A8B5"
    val startEpoch: Long,
    val endEpoch: Long,
    val distanceM: Double,
    val durationS: Long,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val crashCount: Int,
    val syncedAtEpoch: Long,
)

@Entity(
    tableName = "route_points",
    primaryKeys = ["rideId", "seq"],
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
)
data class RoutePointEntity(
    val rideId: Long,
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
)

/** Every crash alert the phone raised and what the rider did about it. */
@Entity(tableName = "crash_events")
data class CrashEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epoch: Long,
    val lat: Double,
    val lon: Double,
    val peakG: Double,
    val outcome: String,         // "cancelled" | "escalated"
    val deviceLabel: String?,
)

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startEpoch DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM route_points WHERE rideId = :rideId ORDER BY seq")
    fun observeRoute(rideId: Long): Flow<List<RoutePointEntity>>

    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM rides")
    fun observeTotalDistanceM(): Flow<Double>

    @Query("SELECT EXISTS(SELECT 1 FROM rides WHERE sourceKey = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRide(ride: RideEntity): Long

    @Insert
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Transaction
    suspend fun insertRideWithRoute(ride: RideEntity, points: List<RoutePointEntity>): Long {
        val id = insertRide(ride)
        if (id > 0) insertPoints(points.map { it.copy(rideId = id) })
        return id
    }

    @Query("DELETE FROM rides")
    suspend fun deleteAllRides()

    @Insert
    suspend fun insertCrash(event: CrashEventEntity)

    @Query("SELECT * FROM crash_events ORDER BY epoch DESC")
    fun observeCrashes(): Flow<List<CrashEventEntity>>
}

@Database(entities = [RideEntity::class, RoutePointEntity::class, CrashEventEntity::class], version = 1, exportSchema = true)
abstract class SmartRideDatabase : RoomDatabase() {
    abstract fun rides(): RideDao

    companion object {
        fun create(context: Context): SmartRideDatabase =
            Room.databaseBuilder(context, SmartRideDatabase::class.java, "smartride.db").build()
    }
}
