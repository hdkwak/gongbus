package com.hidong.gongbus

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        "android.permission.health.READ_EXERCISE_ROUTE"
    )

    fun isAvailable(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    companion object {
        const val SDK_AVAILABLE = HealthConnectClient.SDK_AVAILABLE
        const val SDK_UNAVAILABLE = HealthConnectClient.SDK_UNAVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        return healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun hasAnyPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        // Log all granted permissions to help debug
        android.util.Log.d("HealthConnect", "Total granted permissions: ${granted.size}")
        granted.forEach { android.util.Log.d("HealthConnect", "Granted: $it") }
        
        // We need at least the basic exercise session to do anything
        return granted.any { it.contains("exercise", ignoreCase = true) }
    }

    suspend fun getMissingPermissions(): Set<String> {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return permissions.filter { !granted.contains(it) }.toSet()
    }

    fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()

    fun getSettingsIntent(): Intent {
        return Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    }

    suspend fun fetchRecentActivities(): List<ExerciseSessionRecord> {
        return try {
            val startTime = Instant.now().minus(30, ChronoUnit.DAYS)
            val endTime = Instant.now()
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            
            android.util.Log.d("HealthConnect", "Found ${response.records.size} total activities in last 30 days")
            response.records.forEach { 
                android.util.Log.d("HealthConnect", "Activity: ${it.title}, Type: ${it.exerciseType}, Start: ${it.startTime}")
            }

            // Broaden filter as much as possible
            response.records.filter { 
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING || 
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING ||
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT ||
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_HIKING ||
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            }
        } catch (e: Exception) {
            android.util.Log.e("HealthConnect", "Error fetching activities", e)
            emptyList()
        }
    }

    suspend fun getSessionDetails(session: ExerciseSessionRecord): ActivityDetail {
        val startTime = session.startTime
        val endTime = session.endTime

        // Fetch Heart Rate (Safe Read)
        val hrRecords = try {
            healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            ).records
        } catch (e: Exception) { emptyList() }

        val avgHr = if (hrRecords.isNotEmpty()) {
            hrRecords.flatMap { it.samples }.map { it.beatsPerMinute }.average().toInt()
        } else null

        val maxHr = if (hrRecords.isNotEmpty()) {
            hrRecords.flatMap { it.samples }.map { it.beatsPerMinute }.maxOrNull()?.toInt()
        } else null

        // Fetch Distance (Safe Read)
        val totalDistance = try {
            val distanceRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            ).records
            distanceRecords.map { it.distance.inMeters }.sum().toInt()
        } catch (e: Exception) { null }

        // Fetch Calories (Safe Read)
        val totalCalories = try {
            val calorieRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            ).records
            calorieRecords.map { it.energy.inKilocalories }.sum().toInt()
        } catch (e: Exception) { null }

        return ActivityDetail(
            id = 0,
            title = session.title ?: "Health Connect Run",
            start_time = startTime.toString(),
            distance_meters = totalDistance,
            duration_seconds = ChronoUnit.SECONDS.between(startTime, endTime).toInt(),
            route_line_geojson = null,
            time_series_data = null,
            username = "",
            avatar_url = null,
            avg_heart_rate = avgHr,
            max_heart_rate = maxHr,
            avg_cadence = null,
            total_calories = totalCalories,
            comments = null
        )
    }
}
