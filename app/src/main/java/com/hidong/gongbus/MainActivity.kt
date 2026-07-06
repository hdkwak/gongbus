package com.hidong.gongbus

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.*
import com.hidong.gongbus.ui.theme.GongbusTheme
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// --- Models ---
data class ActivityFeedItem(
    val id: Int,
    val title: String?,
    val start_time: String,
    val distance_meters: Int?,
    val duration_seconds: Int?,
    val route_line_geojson: Any?,
    val username: String,
    val avatar_url: String?,
    val avg_heart_rate: Int?,
    val avg_cadence: Int?,
    val total_calories: Int?,
    val like_count: Long,
    val comment_count: Long
)

data class MetricRecord(
    val heart_rate: Float?,
    val cadence: Float?,
    val altitude: Float?,
    val ground_contact_time: Float?,
    val stride_distance: Float?,
    val speed: Float?,
    val distance: Float?
)

data class Comment(
    val username: String,
    val avatar_url: String?,
    val comment_text: String,
    val created_at: String
)

data class ActivityDetail(
    val id: Int,
    val title: String?,
    val start_time: String,
    val distance_meters: Int?,
    val duration_seconds: Int?,
    val route_line_geojson: Any?,
    val time_series_data: List<MetricRecord>?,
    val username: String,
    val avatar_url: String?,
    val avg_heart_rate: Int?,
    val max_heart_rate: Int?,
    val avg_cadence: Int?,
    val total_calories: Int?,
    val comments: List<Comment>?
)

data class WeeklyMileage(
    val week_start: String,
    val distance_meters: Long
)

data class LeaderboardEntry(
    val username: String,
    val avatar_url: String?,
    val total_meters: Long
)

data class DashboardData(
    val weekly_total_meters: Long,
    val monthly_total_meters: Long,
    val weekly_trend: List<WeeklyMileage>,
    val leaderboard: List<LeaderboardEntry>,
    val activities: List<ActivityFeedItem>
)

data class UserProfile(
    val id: Int,
    val username: String,
    val avatar_url: String?,
    val marathon_goal_sec: Int?,
    val weekly_target_km: Double?,
    val monthly_target_km: Double?,
    val target_lsd_count: Int?,
    val target_race: String?,
    val race_date: String?
)

data class AvatarResponse(val url: String)

data class CommentPayload(val user_id: Int, val comment_text: String)
data class LikePayload(val user_id: Int)

// --- API ---
interface RunningApi {
    @GET("feed")
    suspend fun getFeed(): List<ActivityFeedItem>

    @GET("activities/{id}")
    suspend fun getActivity(@Path("id") id: Int): ActivityDetail

    @DELETE("activities/{id}")
    suspend fun deleteActivity(@Path("id") id: Int): retrofit2.Response<Unit>

    @POST("activities/{id}/like")
    suspend fun likeActivity(@Path("id") id: Int, @Body payload: LikePayload): retrofit2.Response<Unit>

    @POST("activities/{id}/comment")
    suspend fun commentActivity(@Path("id") id: Int, @Body payload: CommentPayload): retrofit2.Response<Unit>

    @GET("users/{id}/dashboard")
    suspend fun getDashboard(@Path("id") id: Int): DashboardData

    @GET("users/{id}")
    suspend fun getUserProfile(@Path("id") id: Int): UserProfile

    @PUT("users/{id}")
    suspend fun updateUserProfile(@Path("id") id: Int, @Body profile: UserProfile): retrofit2.Response<Unit>

    @Multipart
    @POST("upload-avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): AvatarResponse

    @Multipart
    @POST("upload-run")
    suspend fun uploadRun(@Part file: MultipartBody.Part, @Part("user_id") userId: Int)

    companion object {
        private const val BASE_URL = "http://192.168.0.176:3000/"
        fun create(): RunningApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RunningApi::class.java)
    }
}

// --- ViewModel ---
class MainViewModel : ViewModel() {
    private val api = RunningApi.create()
    var activities by mutableStateOf<List<ActivityFeedItem>>(emptyList())
    var selectedActivity by mutableStateOf<ActivityDetail?>(null)
    var dashboardData by mutableStateOf<DashboardData?>(null)
    var userProfile by mutableStateOf<UserProfile?>(null)
    
    var isFeedLoading by mutableStateOf(false)
    var isDetailLoading by mutableStateOf(false)
    var isDashboardLoading by mutableStateOf(false)
    var isProfileLoading by mutableStateOf(false)
    var isUploading by mutableStateOf(false)
    var uploadStatus = mutableStateOf<String?>(null)

    init { 
        fetchFeed()
        fetchDashboard()
        fetchProfile()
    }

    fun fetchFeed() {
        viewModelScope.launch {
            isFeedLoading = true
            try { activities = api.getFeed() } catch (e: Exception) { e.printStackTrace() } finally { isFeedLoading = false }
        }
    }

    fun fetchDashboard() {
        viewModelScope.launch {
            isDashboardLoading = true
            try { dashboardData = api.getDashboard(1) } catch (e: Exception) { e.printStackTrace() } finally { isDashboardLoading = false }
        }
    }

    fun fetchProfile() {
        viewModelScope.launch {
            isProfileLoading = true
            try { userProfile = api.getUserProfile(1) } catch (e: Exception) { e.printStackTrace() } finally { isProfileLoading = false }
        }
    }

    fun saveProfile(context: Context, profile: UserProfile, localAvatarUri: Uri?) {
        viewModelScope.launch {
            try {
                var updatedProfile = profile
                
                if (localAvatarUri != null) {
                    val file = uriToFile(context, localAvatarUri)
                    val body = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/*".toMediaTypeOrNull()))
                    val response = api.uploadAvatar(body)
                    updatedProfile = updatedProfile.copy(avatar_url = response.url)
                }

                val response = api.updateUserProfile(1, updatedProfile)
                if (response.isSuccessful) {
                    userProfile = updatedProfile
                    uploadStatus.value = "Profile updated"
                    fetchFeed()
                }
            } catch (e: Exception) {
                uploadStatus.value = "Update failed: ${e.message}"
            }
        }
    }

    fun deleteActivity(id: Int) {
        viewModelScope.launch {
            try {
                val response = api.deleteActivity(id)
                if (response.isSuccessful) {
                    uploadStatus.value = "Activity deleted"
                    activities = activities.filter { it.id != id }
                    dashboardData = dashboardData?.let { current ->
                        current.copy(activities = current.activities.filter { it.id != id })
                    }
                    fetchDashboard()
                } else {
                    uploadStatus.value = "Delete failed: ${response.code()}"
                }
            } catch (e: Exception) {
                uploadStatus.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun likeActivity(id: Int) {
        viewModelScope.launch {
            try {
                val response = api.likeActivity(id, LikePayload(user_id = 1))
                if (response.isSuccessful) {
                    fetchFeed()
                    uploadStatus.value = "Kudos!"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addComment(id: Int, text: String) {
        viewModelScope.launch {
            try {
                val response = api.commentActivity(id, CommentPayload(user_id = 1, comment_text = text))
                if (response.isSuccessful) {
                    fetchFeed()
                    fetchActivityDetail(id) // Refresh comments list
                    uploadStatus.value = "Comment posted"
                } else {
                    uploadStatus.value = "Failed to post comment: ${response.code()}"
                }
            } catch (e: Exception) {
                uploadStatus.value = "Error: ${e.message}"
            }
        }
    }

    fun fetchActivityDetail(id: Int) {
        viewModelScope.launch {
            selectedActivity = null
            isDetailLoading = true
            try { selectedActivity = api.getActivity(id) } catch (e: Exception) { uploadStatus.value = "Error: ${e.message}" } finally { isDetailLoading = false }
        }
    }

    fun uploadFitFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            isUploading = true
            try {
                val file = uriToFile(context, uri)
                val body = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                api.uploadRun(body, 1)
                uploadStatus.value = "Upload Successful!"
                fetchFeed()
                fetchDashboard()
            } catch (e: Exception) {
                uploadStatus.value = "Upload Failed: ${e.message}"
            } finally { isUploading = false }
        }
    }

    private fun uriToFile(context: Context, uri: Uri): File {
        val fileName = getFileName(context, uri) ?: "temp_file"
        val tempFile = File(context.cacheDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
        return tempFile
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it != -1 } ?: return null)
        }
        return null
    }
}

// --- Navigation ---
sealed class Screen(val title: String, val icon: ImageVector) {
    object Feed : Screen("Feed", Icons.Default.Home)
    object Dashboard : Screen("Dashboard", Icons.Default.Dashboard)
    object Profile : Screen("Profile", Icons.Default.Person)
}

// --- Main UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GongbusTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var showDetailById by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    LaunchedEffect(viewModel.uploadStatus.value) {
        viewModel.uploadStatus.value?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.uploadStatus.value = null
        }
    }

    if (showDetailById != null) {
        BackHandler { showDetailById = null }
        ActivityDetailScreen(viewModel) { showDetailById = null }
    } else {
        var activityToDelete by remember { mutableStateOf<Int?>(null) }
        
        if (activityToDelete != null) {
            AlertDialog(
                onDismissRequest = { activityToDelete = null },
                title = { Text("Delete Activity") },
                text = { Text("Are you sure you want to delete this activity? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = { 
                        activityToDelete?.let { viewModel.deleteActivity(it) }
                        activityToDelete = null
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { activityToDelete = null }) { Text("Cancel") }
                }
            )
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(Screen.Feed, Screen.Dashboard, Screen.Profile).forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, null) },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen,
                            onClick = { 
                                currentScreen = screen
                                when(screen) {
                                    Screen.Dashboard -> viewModel.fetchDashboard()
                                    Screen.Profile -> viewModel.fetchProfile()
                                    else -> {}
                                }
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.Feed -> FeedScreen(
                        viewModel = viewModel,
                        onActivityClick = { id ->
                            showDetailById = id
                            viewModel.fetchActivityDetail(id)
                        },
                        onDeleteClick = { id -> activityToDelete = id }
                    )
                    Screen.Dashboard -> DashboardScreen(
                        viewModel = viewModel
                    )
                    Screen.Profile -> ProfileScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun FeedScreen(viewModel: MainViewModel, onActivityClick: (Int) -> Unit, onDeleteClick: (Int) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.uploadFitFile(context, it) } }
    var activityForComments by remember { mutableStateOf<Int?>(null) }

    if (activityForComments != null) {
        // Fetch details to get comments
        LaunchedEffect(activityForComments) {
            viewModel.fetchActivityDetail(activityForComments!!)
        }
        
        val detail = viewModel.selectedActivity
        var commentText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { activityForComments = null },
            title = { Text("Comments") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.isDetailLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            val comments = detail?.comments ?: emptyList()
                            if (comments.isEmpty()) {
                                item { Text("No comments yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                            }
                            items(comments) { comment ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.LightGray)) {
                                        if (comment.avatar_url != null) {
                                            AsyncImage(model = comment.avatar_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(comment.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                        Text(comment.comment_text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        label = { Text("Add a comment") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (commentText.isNotEmpty()) {
                        viewModel.addComment(activityForComments!!, commentText)
                        commentText = ""
                        // The counts will refresh because addComment calls fetchFeed
                    }
                }) { Text("Post") }
            },
            dismissButton = {
                TextButton(onClick = { activityForComments = null }) { Text("Close") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (viewModel.isFeedLoading || viewModel.isUploading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Recent Runs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            items(viewModel.activities) { activity -> 
                ActivityCard(
                    activity = activity, 
                    onClick = onActivityClick,
                    onDelete = { onDeleteClick(activity.id) },
                    onLike = { viewModel.likeActivity(activity.id) },
                    onComment = { activityForComments = activity.id }
                ) 
            }
        }
        FloatingActionButton(onClick = { launcher.launch("*/*") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityCard(activity: ActivityFeedItem, onClick: (Int) -> Unit, onDelete: () -> Unit, onLike: () -> Unit, onComment: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).clickable { onClick(activity.id) }
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                        if (activity.avatar_url != null) {
                            AsyncImage(
                                model = activity.avatar_url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(activity.username, fontWeight = FontWeight.Bold)
                        Text(activity.start_time.substringBefore("T"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            
            Column(modifier = Modifier.fillMaxWidth().clickable { onClick(activity.id) }) {
                Spacer(Modifier.height(12.dp))
                Text(activity.title ?: "Morning Run", style = MaterialTheme.typography.titleMedium)
                
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SummaryStat("Avg HR", "${activity.avg_heart_rate ?: "--"}")
                    SummaryStat("Avg Cad", "${activity.avg_cadence ?: "--"}")
                    SummaryStat("Calories", "${activity.total_calories ?: "--"}")
                    val distKm = (activity.distance_meters ?: 0) / 1000.0
                    SummaryStat("Dist", "%.1f km".format(distKm))
                }

                Spacer(Modifier.height(12.dp))
                RouteMap(activity.route_line_geojson)
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ThumbUp, contentDescription = "Like", modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(text = activity.like_count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = onComment) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Comment, contentDescription = "Comment", modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(text = activity.comment_count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val data = viewModel.dashboardData
    val profile = viewModel.userProfile

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(24.dp)) {
        Text("My Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        
        if (viewModel.isDashboardLoading || viewModel.isProfileLoading) {
            CircularProgressIndicator()
        } else if (data != null && profile != null) {
            // Race Countdown Section
            profile.race_date?.let { dateStr ->
                val daysLeft = calculateDaysToRace(dateStr)
                if (daysLeft != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = profile.target_race ?: "Target Race", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    daysLeft > 0 -> "D-$daysLeft"
                                    daysLeft == 0L -> "Race Day!"
                                    else -> "Race Completed"
                                },
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = "Scheduled for: $dateStr", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                val weeklyKm = data.weekly_total_meters / 1000f
                val weeklyTarget = profile.weekly_target_km ?: 0.0
                DashboardStat(
                    label = "Weekly",
                    value = "%.1f / %.1f km".format(weeklyKm, weeklyTarget),
                    modifier = Modifier.weight(1f)
                )

                val monthlyKm = data.monthly_total_meters / 1000f
                val monthlyTarget = profile.monthly_target_km ?: 0.0
                DashboardStat(
                    label = "Monthly",
                    value = "%.1f / %.1f km".format(monthlyKm, monthlyTarget),
                    modifier = Modifier.weight(1f)
                )
            }

            // Weekly Mileage Trend
            Text("Weekly Mileage Trend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            WeeklyTrendChart(data.weekly_trend)

            // Leaderboard
            Text("Top Runners (This Month)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LeaderboardSection(data.leaderboard)
        }
    }
}

@Composable
fun LeaderboardSection(entries: List<LeaderboardEntry>) {
    if (entries.isEmpty()) {
        Text("No activity this month yet.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (entry.avatar_url != null) {
                        AsyncImage(
                            model = entry.avatar_url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(text = entry.username, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    text = "%.1f km".format(entry.total_meters / 1000f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun WeeklyTrendChart(trend: List<WeeklyMileage>) {
    if (trend.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) {
            Text("No activity data for trend", color = Color.Gray)
        }
        return
    }

    val maxKm = trend.maxOf { it.distance_meters } / 1000f
    val range = if (maxKm == 0f) 1f else maxKm

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            trend.forEach { week ->
                val km = week.distance_meters / 1000f
                val normalizedHeight = (km / range).coerceIn(0.05f, 1f)
                
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = "%.1f".format(km),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(normalizedHeight * 0.8f) // Leave room for text
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                    // Extract MM/DD from YYYY-MM-DD
                    val label = try { 
                        val parts = week.week_start.split("-")
                        "${parts[1]}/${parts[2]}"
                    } catch (e: Exception) { "" }
                    
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(viewModel: MainViewModel) {
    val profile = viewModel.userProfile
    val context = LocalContext.current
    
    if (viewModel.isProfileLoading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    } else if (profile != null) {
        var username by remember { mutableStateOf(profile.username) }
        var avatarUrl by remember { mutableStateOf(profile.avatar_url) }
        var localAvatarUri by remember { mutableStateOf<Uri?>(null) }
        var goalTimeStr by remember { mutableStateOf(formatSecondsToHHMMSS(profile.marathon_goal_sec)) }
        var weeklyTarget by remember { mutableStateOf(profile.weekly_target_km?.toString() ?: "") }
        var monthlyTarget by remember { mutableStateOf(profile.monthly_target_km?.toString() ?: "") }
        var targetLsd by remember { mutableStateOf(profile.target_lsd_count?.toString() ?: "") }
        var targetRace by remember { mutableStateOf(profile.target_race ?: "") }
        var raceDate by remember { mutableStateOf(profile.race_date ?: "") }

        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
            onResult = { uri ->
                if (uri != null) {
                    localAvatarUri = uri
                }
            }
        )

        val calendar = Calendar.getInstance()
        val datePickerDialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                raceDate = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("User Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { 
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                val imageToDisplay = localAvatarUri ?: avatarUrl
                if (imageToDisplay != null) {
                    AsyncImage(
                        model = imageToDisplay,
                        contentDescription = "Profile Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.AddAPhoto, contentDescription = "Add Photo", modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Tap to change photo", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth())
            
            OutlinedTextField(
                value = goalTimeStr, 
                onValueChange = { goalTimeStr = it }, 
                label = { Text("Marathon Goal Time (HH:MM:SS)") }, 
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("04:00:00") }
            )

            OutlinedTextField(
                value = weeklyTarget, 
                onValueChange = { weeklyTarget = it }, 
                label = { Text("Target Weekly Mileage (km)") }, 
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            OutlinedTextField(
                value = monthlyTarget, 
                onValueChange = { monthlyTarget = it }, 
                label = { Text("Target Monthly Mileage (km)") }, 
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            OutlinedTextField(
                value = targetLsd, 
                onValueChange = { targetLsd = it }, 
                label = { Text("Target number of LSD") }, 
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = targetRace, 
                onValueChange = { targetRace = it }, 
                label = { Text("Target Race") }, 
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = raceDate, 
                onValueChange = { }, 
                label = { Text("Race Date") }, 
                modifier = Modifier.fillMaxWidth().clickable { datePickerDialog.show() },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )

            Button(
                onClick = {
                    val updated = profile.copy(
                        username = username,
                        avatar_url = avatarUrl, // Will be updated if localAvatarUri is present
                        marathon_goal_sec = parseHHMMSSToSeconds(goalTimeStr),
                        weekly_target_km = weeklyTarget.toDoubleOrNull(),
                        monthly_target_km = monthlyTarget.toDoubleOrNull(),
                        target_lsd_count = targetLsd.toIntOrNull(),
                        target_race = targetRace.takeIf { it.isNotEmpty() },
                        race_date = raceDate.takeIf { it.isNotEmpty() }
                    )
                    viewModel.saveProfile(context, updated, localAvatarUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Profile")
            }
        }
    }
}

fun formatSecondsToHHMMSS(totalSeconds: Int?): String {
    if (totalSeconds == null || totalSeconds <= 0) return ""
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

fun parseHHMMSSToSeconds(timeStr: String): Int? {
    val parts = timeStr.split(":").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 3) return null
    return parts[0] * 3600 + parts[1] * 60 + parts[2]
}

@Composable
fun DashboardStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val activity = viewModel.selectedActivity
    var lapDistanceKm by remember { mutableStateOf(1.0f) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activity?.title ?: "Loading...", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { padding ->
        if (viewModel.isDetailLoading) Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        else if (activity != null) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(20.dp)) {
                RouteMap(activity.route_line_geojson, Modifier.height(250.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SummaryStat("Avg HR", "${activity.avg_heart_rate ?: "--"}")
                    SummaryStat("Max HR", "${activity.max_heart_rate ?: "--"}")
                    SummaryStat("Avg Cad", "${activity.avg_cadence ?: "--"}")
                    SummaryStat("Calories", "${activity.total_calories ?: "--"}")
                }
                Column {
                    Text("Lap Distance (km)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { dist ->
                            FilterChip(selected = lapDistanceKm == dist, onClick = { lapDistanceKm = dist }, label = { Text("${dist}km") })
                        }
                    }
                }
                val splits = remember(activity.time_series_data, lapDistanceKm) { calculateSplits(activity.time_series_data ?: emptyList(), lapDistanceKm) }
                MetricChart("Heart Rate (bpm)", splits.map { it.avgHeartRate })
                MetricChart("Pace (min/km)", splits.map { it.avgPace }, inverted = true, isPace = true)
                MetricChart("Cadence (spm)", splits.map { it.avgCadence })
                MetricChart("Elevation (m)", splits.map { it.avgAltitude })
                MetricChart("Stride Distance (m)", splits.map { it.avgStrideDistance })
                MetricChart("Ground Contact Time (ms)", splits.map { it.avgGct })
            }
        }
    }
}

data class ActivitySplit(val lapNumber: Int, val avgPace: Float?, val avgHeartRate: Float?, val avgCadence: Float?, val avgAltitude: Float?, val avgStrideDistance: Float?, val avgGct: Float?)

fun calculateSplits(data: List<MetricRecord>, lapDistKm: Float): List<ActivitySplit> {
    if (data.isEmpty()) return emptyList()
    val lapDistMeters = lapDistKm * 1000f
    val splits = mutableListOf<ActivitySplit>()
    var currentLap = 1
    var lapStartIndex = 0
    val startDistance = data.firstOrNull()?.distance ?: 0f
    for (i in data.indices) {
        val currentDistance = data[i].distance ?: 0f
        val relativeDistance = currentDistance - startDistance
        if (relativeDistance >= currentLap * lapDistMeters || i == data.size - 1) {
            val lapData = data.subList(lapStartIndex, i + 1)
            if (lapData.isNotEmpty()) {
                val avgSpeed = lapData.mapNotNull { it.speed }.average().toFloat()
                val pace = if (avgSpeed > 0) (60.0 / (avgSpeed * 3.6)).toFloat() else null
                splits.add(ActivitySplit(currentLap, pace, lapData.mapNotNull { it.heart_rate }.average().takeIf { !it.isNaN() }?.toFloat(), lapData.mapNotNull { it.cadence }.average().takeIf { !it.isNaN() }?.toFloat(), lapData.mapNotNull { it.altitude }.average().takeIf { !it.isNaN() }?.toFloat(), lapData.mapNotNull { it.stride_distance }.average().takeIf { !it.isNaN() }?.toFloat(), lapData.mapNotNull { it.ground_contact_time }.average().takeIf { !it.isNaN() }?.toFloat()))
            }
            lapStartIndex = i
            currentLap++
        }
    }
    return splits
}

@Composable
fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MetricChart(title: String, data: List<Float?>?, inverted: Boolean = false, isPace: Boolean = false) {
    val vals = data?.filterNotNull() ?: emptyList()
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        if (vals.isEmpty()) Text("No data")
        else {
            val sampled = if (vals.size > 40) { val s = vals.size / 40; vals.filterIndexed { i, _ -> i % s == 0 } } else vals
            val max = sampled.maxOrNull() ?: 1f
            val min = sampled.minOrNull() ?: 0f
            val avg = vals.average().toFloat()
            val range = if (max == min) 1f else max - min
            val formatValue: (Float) -> String = { v ->
                if (isPace) { val ts = (v * 60).roundToInt(); "%d:%02d".format(ts / 60, ts % 60) }
                else "%.1f".format(v)
            }
            Row(Modifier.fillMaxWidth().height(130.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.fillMaxHeight(), Arrangement.SpaceBetween, Alignment.End) {
                    Text(formatValue(if (inverted) min else max), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                    Text(formatValue(avg), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontSize = 9.sp)
                    Text(formatValue(if (inverted) max else min), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f).fillMaxWidth(), Arrangement.spacedBy(2.dp), Alignment.Bottom) {
                        sampled.forEach { v -> Box(Modifier.weight(1f).fillMaxHeight((if (inverted) (max - v) / range else (v - min) / range).coerceIn(0.05f, 1f)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))) }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Distance (Laps)", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray) }
                }
            }
        }
    }
}

@Composable
fun RouteMap(geoJson: Any?, modifier: Modifier = Modifier.height(150.dp)) {
    val points = remember(geoJson) { parseGeoJson(geoJson) }
    if (points.isEmpty()) Box(modifier.fillMaxWidth().background(Color.LightGray, RoundedCornerShape(8.dp)), Alignment.Center) { Text("No GPS") }
    else GoogleMap(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), cameraPositionState = rememberCameraPositionState { val b = LatLngBounds.builder(); points.forEach { b.include(it) }; position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(b.build().center, 12f) }, googleMapOptionsFactory = { com.google.android.gms.maps.GoogleMapOptions().liteMode(true) }) { Polyline(points, color = MaterialTheme.colorScheme.primary, width = 8f) }
}

fun parseGeoJson(json: Any?): List<LatLng> {
    return try {
        val m = json as Map<*, *>
        (m["coordinates"] as List<*>).map { val c = it as List<*>; LatLng(c[1] as Double, c[0] as Double) }
    } catch (e: Exception) { emptyList() }
}

fun calculateDaysToRace(raceDateStr: String): Long? {
    if (raceDateStr.isEmpty()) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val raceDate = sdf.parse(raceDateStr) ?: return null
        
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        
        val diff = raceDate.time - today.time
        diff / (1000 * 60 * 60 * 24)
    } catch (e: Exception) {
        null
    }
}
