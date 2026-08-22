package com.hidong.gongbus

import android.app.Application
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.AndroidViewModel
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
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
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
    val user_id: Int,
    val strava_id: Long?,
    val title: String?,
    val start_time: String,
    val distance_meters: Int?,
    val duration_seconds: Int?,
    val route_line_geojson: Any?,
    val username: String?,
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
    val user_id: Int = 0,
    val strava_id: Long? = null,
    val title: String?,
    val start_time: String,
    val distance_meters: Int?,
    val duration_seconds: Int?,
    val route_line_geojson: Any?,
    val time_series_data: List<MetricRecord>?,
    val username: String?,
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
    val user_id: Int,
    val username: String?,
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
    val race_date: String?,
    val strava_athlete_id: Long? = null,
    val ai_provider: String? = "openai",
    val ai_api_key: String? = null
)

data class ChatMessage(
    val role: String,
    val message: String,
    val created_at: String? = null
)

data class ChatRequest(val message: String)

data class StravaLinkResponse(val url: String)

data class AvatarResponse(val url: String)

data class CommentPayload(val user_id: Int, val comment_text: String)
data class LikePayload(val user_id: Int)

// --- API ---
interface RunningApi {
    @GET("feed")
    suspend fun getFeed(
        @Query("user_id") userId: Int? = null,
        @Query("page") page: Int? = null,
        @Query("per_page") perPage: Int? = null
    ): List<ActivityFeedItem>

    @GET("activities/{id}")
    suspend fun getActivity(@Path("id") id: Int): ActivityDetail

    @PUT("activities/{id}")
    suspend fun updateActivity(@Path("id") id: Int, @Body payload: Map<String, String?>): retrofit2.Response<Unit>

    @DELETE("activities/{id}")
    suspend fun deleteActivity(@Path("id") id: Int): retrofit2.Response<Unit>

    @POST("activities/{id}/like")
    suspend fun likeActivity(@Path("id") id: Int, @Body payload: LikePayload): retrofit2.Response<Unit>

    @POST("activities/{id}/comment")
    suspend fun commentActivity(@Path("id") id: Int, @Body payload: CommentPayload): retrofit2.Response<Unit>

    @GET("users/{id}/dashboard")
    suspend fun getDashboard(@Path("id") id: Int): DashboardData

    @GET("users")
    suspend fun getUsers(): List<UserProfile>

    @POST("users/{id}/strava-link")
    suspend fun getStravaLinkUrl(@Path("id") id: Int): StravaLinkResponse

    @POST("users/{id}/strava-sync")
    suspend fun syncStrava(@Path("id") id: Int): retrofit2.Response<Unit>

    @GET("users/{id}")
    suspend fun getUserProfile(@Path("id") id: Int): UserProfile

    @POST("users")
    suspend fun createProfile(@Body profile: UserProfile): UserProfile

    @PUT("users/{id}")
    suspend fun updateUserProfile(@Path("id") id: Int, @Body profile: UserProfile): retrofit2.Response<Unit>

    @Multipart
    @POST("upload-avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): AvatarResponse

    @POST("activities")
    suspend fun syncActivity(@Body activity: ActivityDetail): retrofit2.Response<Unit>

    @GET("activities/{id}/chat")
    suspend fun getChatHistory(@Path("id") id: Int): List<ChatMessage>

    @POST("activities/{id}/chat")
    suspend fun askAiCoach(@Path("id") id: Int, @Body payload: ChatRequest): ChatMessage

    @Multipart
    @POST("upload-run")
    suspend fun uploadRun(
        @Part file: MultipartBody.Part, 
        @Part("user_id") userId: Int,
        @Part("title") title: okhttp3.RequestBody? = null
    )

    companion object {
        private const val BASE_URL = "https://gongbus-api.onrender.com/" // REPLACE WITH YOUR RENDER URL

        fun create(): RunningApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RunningApi::class.java)
        }
    }
}

// --- ViewModel ---
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val api = RunningApi.create()
    private val prefs = application.getSharedPreferences("gongbus_prefs", Context.MODE_PRIVATE)
    private val db = AppDatabase.getDatabase(application)
    private val activityDao = db.activityDao()
    private val gson = Gson()

    var activities by mutableStateOf<List<ActivityFeedItem>>(emptyList())
    var members by mutableStateOf<List<UserProfile>>(emptyList())
    var selectedActivity by mutableStateOf<ActivityDetail?>(null)
    var dashboardData by mutableStateOf<DashboardData?>(null)
    var userProfile by mutableStateOf<UserProfile?>(null)
    var profileNotFound by mutableStateOf(false)

    var filterUserId by mutableStateOf<Int?>(null)
    var filterUsername by mutableStateOf<String?>(null)
    
    var currentPage by mutableStateOf(1)
    var hasMore by mutableStateOf(true)
    
    var isFeedLoading by mutableStateOf(false)
    var isDetailLoading by mutableStateOf(false)
    var isDashboardLoading by mutableStateOf(false)
    var isProfileLoading by mutableStateOf(false)
    var isMembersLoading by mutableStateOf(false)
    var isUploading by mutableStateOf(false)
    var isChatLoading by mutableStateOf(false)
    var uploadStatus = mutableStateOf<String?>(null)
    
    var chatHistory by mutableStateOf<List<ChatMessage>>(emptyList())

    init { 
        val savedId = prefs.getInt("user_id", -1)
        if (savedId != -1) {
            fetchProfile(savedId)
        } else {
            profileNotFound = true
        }
        
        // Load from local cache immediately
        viewModelScope.launch {
            loadLocalActivities()
            fetchFeed() // Then sync with network
        }
    }

    private suspend fun loadLocalActivities() {
        val entities = withContext(Dispatchers.IO) {
            if (filterUserId != null) {
                activityDao.getByUserId(filterUserId!!)
            } else {
                activityDao.getAll()
            }
        }
        activities = entities.map { it.toModel(gson) }
    }

    fun fetchFeed(loadMore: Boolean = false) {
        if (loadMore && !hasMore) return
        if (loadMore && isFeedLoading) return

        viewModelScope.launch {
            isFeedLoading = true
            val pageToFetch = if (loadMore) currentPage else 1
            val pageSize = 30

            try { 
                val currentFilter = filterUserId
                val networkActivities = api.getFeed(currentFilter, page = pageToFetch, perPage = pageSize)
                
                if (networkActivities.isEmpty()) {
                    hasMore = false
                } else {
                    // Save to local cache (Upsert)
                    withContext(Dispatchers.IO) {
                        activityDao.insertAll(networkActivities.map { it.toEntity(gson) })
                    }
                    
                    // If we got fewer items than requested, there are no more pages
                    if (networkActivities.size < pageSize) {
                        hasMore = false
                    } else {
                        currentPage = pageToFetch + 1
                        hasMore = true
                    }
                }
                
                // Reload from local to ensure UI is in sync with cache
                loadLocalActivities()
            } catch (e: Exception) { 
                uploadStatus.value = "Feed error: ${e.message}"
                e.printStackTrace() 
            } finally { isFeedLoading = false }
        }
    }

    fun setFeedFilter(userId: Int?, username: String?) {
        filterUserId = userId
        filterUsername = username
        currentPage = 1
        hasMore = true
        viewModelScope.launch {
            loadLocalActivities()
            fetchFeed()
        }
    }

    fun fetchMembers() {
        viewModelScope.launch {
            isMembersLoading = true
            try {
                members = api.getUsers()
            } catch (e: Exception) {
                e.printStackTrace()
                uploadStatus.value = "Failed to load members"
            } finally {
                isMembersLoading = false
            }
        }
    }

    fun fetchDashboard() {
        val userId = userProfile?.id ?: prefs.getInt("user_id", -1)
        if (userId == -1) return

        viewModelScope.launch {
            isDashboardLoading = true
            try { dashboardData = api.getDashboard(userId) } catch (e: Exception) { e.printStackTrace() } finally { isDashboardLoading = false }
        }
    }

    fun fetchProfile(id: Int? = null) {
        val targetId = id ?: userProfile?.id ?: prefs.getInt("user_id", -1)
        if (targetId == -1) {
            profileNotFound = true
            return
        }

        viewModelScope.launch {
            isProfileLoading = true
            profileNotFound = false
            try { 
                userProfile = api.getUserProfile(targetId) 
                fetchDashboard()
            } catch (e: Exception) { 
                if (e.message?.contains("404") == true) {
                    profileNotFound = true
                }
                e.printStackTrace() 
            } finally { 
                isProfileLoading = false 
            }
        }
    }

    fun createInitialProfile(context: Context, username: String) {
        viewModelScope.launch {
            isProfileLoading = true
            try {
                val newProfile = UserProfile(
                    id = 0,
                    username = username,
                    avatar_url = null,
                    marathon_goal_sec = null,
                    weekly_target_km = null,
                    monthly_target_km = null,
                    target_lsd_count = null,
                    target_race = null,
                    race_date = null
                )
                val created = api.createProfile(newProfile)
                userProfile = created
                profileNotFound = false
                
                // SAVE THE NEW ID LOCALLY
                prefs.edit().putInt("user_id", created.id).apply()
                
                uploadStatus.value = "Profile created!"
                fetchFeed()
                fetchDashboard()
            } catch (e: Exception) {
                uploadStatus.value = "Failed to create profile: ${e.message}"
            } finally {
                isProfileLoading = false
            }
        }
    }

    fun saveProfile(context: Context, profile: UserProfile, localAvatarUri: Uri?) {
        viewModelScope.launch {
            try {
                var updatedProfile = profile
                val userId = userProfile?.id ?: prefs.getInt("user_id", -1)
                if (userId == -1) return@launch
                
                if (localAvatarUri != null) {
                    val file = uriToFile(context, localAvatarUri)
                    val body = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/*".toMediaTypeOrNull()))
                    val response = api.uploadAvatar(body)
                    updatedProfile = updatedProfile.copy(avatar_url = response.url)
                }

                val response = api.updateUserProfile(userId, updatedProfile)
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

    fun linkStrava(context: Context) {
        val userId = userProfile?.id ?: return
        viewModelScope.launch {
            try {
                val response = api.getStravaLinkUrl(userId)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(response.url))
                context.startActivity(intent)
            } catch (e: Exception) {
                uploadStatus.value = "Failed to start Strava link: ${e.message}"
            }
        }
    }

    fun syncFromStrava() {
        val userId = userProfile?.id ?: return
        viewModelScope.launch {
            isUploading = true
            try {
                val response = api.syncStrava(userId)
                if (response.isSuccessful) {
                    uploadStatus.value = "Strava sync successful"
                    fetchFeed()
                } else {
                    uploadStatus.value = "Strava sync failed: ${response.code()}"
                }
            } catch (e: Exception) {
                uploadStatus.value = "Strava sync error: ${e.message}"
            } finally {
                isUploading = false
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
                    // Delete from local cache
                    withContext(Dispatchers.IO) {
                        activityDao.deleteById(id)
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

    fun updateActivityTitle(id: Int, newTitle: String) {
        viewModelScope.launch {
            try {
                val response = api.updateActivity(id, mapOf("title" to newTitle))
                if (response.isSuccessful) {
                    uploadStatus.value = "Title updated!"
                    fetchFeed()
                    if (selectedActivity?.id == id) {
                        fetchActivityDetail(id)
                    }
                } else {
                    uploadStatus.value = "Update failed: ${response.code()}"
                }
            } catch (e: Exception) {
                uploadStatus.value = "Update error: ${e.message}"
            }
        }
    }

    fun likeActivity(id: Int) {
        viewModelScope.launch {
            try {
                val userId = userProfile?.id ?: prefs.getInt("user_id", -1)
                if (userId == -1) return@launch
                val response = api.likeActivity(id, LikePayload(user_id = userId))
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
                val userId = userProfile?.id ?: prefs.getInt("user_id", -1)
                if (userId == -1) return@launch
                val response = api.commentActivity(id, CommentPayload(user_id = userId, comment_text = text))
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
            try { 
                selectedActivity = api.getActivity(id)
                chatHistory = api.getChatHistory(id)
            } catch (e: Exception) { 
                uploadStatus.value = "Error: ${e.message}"
            } finally { isDetailLoading = false }
        }
    }

    fun sendMessageToCoach(activityId: Int, message: String) {
        viewModelScope.launch {
            isChatLoading = true
            // Local echo
            chatHistory = chatHistory + ChatMessage("user", message)
            try {
                val response = api.askAiCoach(activityId, ChatRequest(message))
                chatHistory = chatHistory + response
            } catch (e: Exception) {
                uploadStatus.value = "Coach error: ${e.message}"
            } finally {
                isChatLoading = false
            }
        }
    }

    fun uploadFitFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            isUploading = true
            try {
                val userId = userProfile?.id ?: prefs.getInt("user_id", -1)
                if (userId == -1) {
                    uploadStatus.value = "Please create a profile first"
                    return@launch
                }
                val file = uriToFile(context, uri)
                val body = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                
                // Use filename without extension as default title
                val defaultTitle = file.nameWithoutExtension.replace("_", " ").replace("-", " ")
                val titleBody = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), defaultTitle)
                
                api.uploadRun(body, userId, titleBody)
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
    object Members : Screen("Members", Icons.Default.Group)
    object Profile : Screen("Profile", Icons.Default.Person)
}

// --- Main UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Add global exception handler for easier debugging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("GongbusCrash", "Uncaught exception in thread ${thread.name}", throwable)
        }

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
                    listOf(Screen.Feed, Screen.Dashboard, Screen.Members, Screen.Profile).forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, null) },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen,
                            onClick = { 
                                currentScreen = screen
                                when(screen) {
                                    Screen.Dashboard -> viewModel.fetchDashboard()
                                    Screen.Members -> viewModel.fetchMembers()
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
                        viewModel = viewModel,
                        onUserSelected = { currentScreen = Screen.Feed }
                    )
                    Screen.Members -> MembersScreen(
                        viewModel = viewModel,
                        onUserSelected = { currentScreen = Screen.Feed }
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
    var activityForTitleEdit by remember { mutableStateOf<ActivityFeedItem?>(null) }

    if (activityForTitleEdit != null) {
        var newTitle by remember { mutableStateOf(activityForTitleEdit?.title ?: "") }
        AlertDialog(
            onDismissRequest = { activityForTitleEdit = null },
            title = { Text("Edit Activity Name") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Activity Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activityForTitleEdit?.let { viewModel.updateActivityTitle(it.id, newTitle) }
                    activityForTitleEdit = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { activityForTitleEdit = null }) { Text("Cancel") }
            }
        )
    }

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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(comment.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.width(8.dp))
                                            Text(formatUtcToLocal(comment.created_at, true), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
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
            item {
                Column {
                    Text("Recent Runs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = viewModel.filterUserId == null,
                            onClick = { viewModel.setFeedFilter(null, null) },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = viewModel.filterUserId == viewModel.userProfile?.id && viewModel.filterUserId != null,
                            onClick = { viewModel.setFeedFilter(viewModel.userProfile?.id, "Me") },
                            label = { Text("Mine") }
                        )
                        
                        Spacer(Modifier.weight(1f))
                        
                        if (viewModel.userProfile?.strava_athlete_id != null) {
                            IconButton(onClick = {
                                viewModel.syncFromStrava()
                            }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync Strava")
                            }
                        }
                    }
                    if (viewModel.filterUserId != null && viewModel.filterUserId != viewModel.userProfile?.id) {
                        FilterChip(
                            selected = true,
                            onClick = { },
                            label = { Text("User: ${viewModel.filterUsername}") },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    Modifier.size(16.dp).clickable { viewModel.setFeedFilter(null, null) }
                                )
                            }
                        )
                    }
                }
            }
            items(viewModel.activities) { activity -> 
                ActivityCard(
                    activity = activity, 
                    onClick = onActivityClick,
                    onDelete = { onDeleteClick(activity.id) },
                    onLike = { viewModel.likeActivity(activity.id) },
                    onComment = { activityForComments = activity.id },
                    onUserClick = { id, name -> viewModel.setFeedFilter(id, name) },
                    onEditTitle = { activityForTitleEdit = activity }
                ) 
            }

            if (viewModel.hasMore && viewModel.activities.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (viewModel.isFeedLoading) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            TextButton(onClick = { viewModel.fetchFeed(loadMore = true) }) {
                                Text("Load More")
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { launcher.launch("*/*") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityCard(
    activity: ActivityFeedItem, 
    onClick: (Int) -> Unit, 
    onDelete: () -> Unit, 
    onLike: () -> Unit, 
    onComment: () -> Unit,
    onUserClick: (Int, String) -> Unit,
    onEditTitle: (String) -> Unit
) {
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
                        Text(
                            activity.username ?: "Unknown Runner", 
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onUserClick(activity.user_id, activity.username ?: "Unknown") }
                        )
                        Text(formatUtcToLocal(activity.start_time), style = MaterialTheme.typography.bodySmall)
                    }
                }
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Name") },
                            onClick = {
                                showMenu = false
                                onEditTitle(activity.title ?: "")
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Filter by this runner") },
                            onClick = {
                                showMenu = false
                                onUserClick(activity.user_id, activity.username ?: "Unknown")
                            },
                            leadingIcon = { Icon(Icons.Default.FilterList, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Color.Red) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                        )
                    }
                }
            }
            
            Column(modifier = Modifier.fillMaxWidth().clickable { onClick(activity.id) }) {
                Spacer(Modifier.height(12.dp))
                Text(activity.title ?: "Morning Run", style = MaterialTheme.typography.titleMedium)
                
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SummaryStat("Avg HR", (activity.avg_heart_rate ?: "--").toString())
                    SummaryStat("Avg Cad", activity.avg_cadence?.let { if (it < 120) it * 2 else it }?.toString() ?: "--")
                    SummaryStat("Calories", (activity.total_calories ?: "--").toString())
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
                Spacer(Modifier.weight(1f))
                val context = LocalContext.current
                IconButton(onClick = { shareActivity(context, activity) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel, onUserSelected: () -> Unit) {
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
            LeaderboardSection(data.leaderboard) { userId, username ->
                viewModel.setFeedFilter(userId, username)
                onUserSelected()
            }
        }
    }
}

@Composable
fun LeaderboardSection(entries: List<LeaderboardEntry>, onUserClick: (Int, String) -> Unit) {
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
                    .clickable { onUserClick(entry.user_id, entry.username ?: "Unknown") }
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
                Text(text = entry.username ?: "Unknown", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
    } else if (viewModel.profileNotFound) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Person, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
            Spacer(Modifier.height(16.dp))
            Text("No profile found.", style = MaterialTheme.typography.headlineSmall)
            Text("Please create your profile to start using Gongbus.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            
            var newUsername by remember { mutableStateOf("") }
            OutlinedTextField(
                value = newUsername,
                onValueChange = { newUsername = it },
                label = { Text("Enter Username") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (newUsername.isNotEmpty()) viewModel.createInitialProfile(context, newUsername) },
                modifier = Modifier.fillMaxWidth(),
                enabled = newUsername.isNotEmpty()
            ) {
                Text("Create Profile")
            }
        }
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

            Spacer(Modifier.height(8.dp))
            Text("AI Coach Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            
            var aiProvider by remember { mutableStateOf(profile.ai_provider ?: "openai") }
            var aiApiKey by remember { mutableStateOf(profile.ai_api_key ?: "") }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select AI Provider", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("openai" to "OpenAI", "gemini" to "Gemini", "claude" to "Claude").forEach { (id, label) ->
                        FilterChip(
                            selected = aiProvider == id,
                            onClick = { aiProvider = id },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = aiApiKey,
                onValueChange = { aiApiKey = it },
                label = { Text(when(aiProvider) {
                    "openai" -> "OpenAI API Key"
                    "gemini" -> "Gemini API Key"
                    "claude" -> "Claude API Key"
                    else -> "API Key"
                }) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(when(aiProvider) {
                    "openai" -> "sk-..."
                    "gemini" -> "AIza..."
                    "claude" -> "sk-ant-..."
                    else -> ""
                }) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )

            Button(
                onClick = {
                    val updated = profile.copy(
                        username = username,
                        avatar_url = avatarUrl,
                        marathon_goal_sec = parseHHMMSSToSeconds(goalTimeStr),
                        weekly_target_km = weeklyTarget.toDoubleOrNull(),
                        monthly_target_km = monthlyTarget.toDoubleOrNull(),
                        target_lsd_count = targetLsd.toIntOrNull(),
                        target_race = targetRace.takeIf { it.isNotEmpty() },
                        race_date = raceDate.takeIf { it.isNotEmpty() },
                        ai_provider = aiProvider,
                        ai_api_key = aiApiKey.takeIf { it.isNotEmpty() }
                    )
                    viewModel.saveProfile(context, updated, localAvatarUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Profile")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            
            Text("Automatic Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Strava Sync", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (profile.strava_athlete_id != null) {
                            Text("Linked", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        } else {
                            Text("Not Linked", color = Color.Gray)
                        }
                    }
                    Text(
                        "Gongbus will automatically pull your activities from Strava (including maps). Connect Garmin to Strava first!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Button(
                        onClick = { viewModel.linkStrava(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (profile.strava_athlete_id != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (profile.strava_athlete_id != null) "Re-link Strava" else "Link Strava Account")
                    }
                }
            }
        }
    }
}

@Composable
fun MembersScreen(viewModel: MainViewModel, onUserSelected: () -> Unit) {
    val members = viewModel.members
    
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Community Members", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        if (viewModel.isMembersLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else if (members.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No members found yet.", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members) { user ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.setFeedFilter(user.id, user.username)
                            onUserSelected()
                        },
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                                if (user.avatar_url != null) {
                                    AsyncImage(model = user.avatar_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(user.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("ID: ${user.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CoachChatDialog(viewModel: MainViewModel, activityId: Int, onDismiss: () -> Unit) {
    var messageText by remember { mutableStateOf("") }
    val history = viewModel.chatHistory

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Coach Analysis")
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (history.isEmpty()) {
                        item {
                            Text(
                                "Ask me anything about this run! I know your goals and your performance data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    items(history) { msg ->
                        val isCoach = msg.role == "assistant"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isCoach) Alignment.Start else Alignment.End
                        ) {
                            Surface(
                                color = if (isCoach) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    msg.message,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    if (viewModel.isChatLoading) {
                        item {
                            CircularProgressIndicator(
                                Modifier
                                    .size(24.dp)
                                    .align(Alignment.Start)
                                    .padding(4.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("How was my heart rate?") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (messageText.isNotEmpty()) {
                                    viewModel.sendMessageToCoach(activityId, messageText)
                                    messageText = ""
                                }
                            },
                            enabled = !viewModel.isChatLoading && messageText.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Send, null)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var showChatCoach by remember { mutableStateOf(false) }

    if (showChatCoach && activity != null) {
        CoachChatDialog(viewModel, activity.id) { showChatCoach = false }
    }

    if (showEditTitleDialog && activity != null) {
        var newTitle by remember { mutableStateOf(activity.title ?: "") }
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            title = { Text("Edit Activity Name") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Activity Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateActivityTitle(activity.id, newTitle)
                    showEditTitleDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitleDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activity?.title ?: "Loading...", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    if (activity != null) {
                        IconButton(onClick = { showEditTitleDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Title", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        floatingActionButton = {
            if (activity != null) {
                ExtendedFloatingActionButton(
                    onClick = { showChatCoach = true },
                    icon = { Icon(Icons.Default.SmartToy, null) },
                    text = { Text("Ask Coach") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    ) { padding ->
        if (viewModel.isDetailLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        } else if (activity != null) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(20.dp)) {
                RouteMap(activity.route_line_geojson, Modifier.height(250.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SummaryStat("Avg HR", (activity.avg_heart_rate ?: "--").toString())
                    SummaryStat("Max HR", (activity.max_heart_rate ?: "--").toString())
                    SummaryStat("Avg Cad", activity.avg_cadence?.let { if (it < 120) it * 2 else it }?.toString() ?: "--")
                    SummaryStat("Calories", (activity.total_calories ?: "--").toString())
                }
                Column {
                    Text("Lap Distance (km)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { dist ->
                            FilterChip(selected = lapDistanceKm == dist, onClick = { lapDistanceKm = dist }, label = { Text("${dist}km") })
                        }
                    }
                }
                val splits = remember(activity.time_series_data, lapDistanceKm) { 
                    calculateSplits(activity.time_series_data ?: emptyList(), lapDistanceKm).map { split ->
                        split.copy(avgCadence = split.avgCadence?.let { if (it < 120) it * 2 else it })
                    }
                }
                MetricChart("Heart Rate (bpm)", splits.map { it.avgHeartRate })
                MetricChart("Pace (min/km)", splits.map { it.avgPace }, inverted = true, isPace = true)
                MetricChart("Cadence (spm)", splits.map { it.avgCadence })
                MetricChart("Elevation (m)", splits.map { it.avgAltitude })
                MetricChart("Stride Distance (m)", splits.map { it.avgStrideDistance })
                MetricChart("Ground Contact Time (ms)", splits.map { it.avgGct })
                
                Spacer(Modifier.height(80.dp)) // room for FAB
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Error, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("Failed to load activity detail.")
                    Button(onClick = { 
                        // Retry logic
                    }) {
                        Text("Retry")
                    }
                }
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
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (selectedIndex != null && selectedIndex!! < vals.size) {
                val v = vals[selectedIndex!!]
                val formatValue: (Float) -> String = { valF ->
                    if (isPace) { val ts = (valF * 60).roundToInt(); "%d:%02d".format(ts / 60, ts % 60) }
                    else "%.1f".format(valF)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "Lap ${selectedIndex!! + 1}: ${formatValue(v)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (vals.isEmpty()) {
            Text("No data", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            val max = vals.maxOrNull() ?: 1f
            val min = vals.minOrNull() ?: 0f
            val avg = vals.average().toFloat()
            val range = if (max == min) 1f else max - min
            
            val formatLabel: (Float) -> String = { v ->
                if (isPace) { val ts = (v * 60).roundToInt(); "%d:%02d".format(ts / 60, ts % 60) }
                else v.roundToInt().toString()
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Y-Axis Labels
                Column(Modifier.fillMaxHeight().width(35.dp), Arrangement.SpaceBetween, Alignment.End) {
                    Text(formatLabel(if (inverted) min else max), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                    Text(formatLabel(avg), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontSize = 9.sp)
                    Text(formatLabel(if (inverted) max else min), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                }
                
                Spacer(Modifier.width(8.dp))
                
                // Chart Area
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        Arrangement.spacedBy(2.dp),
                        Alignment.Bottom
                    ) {
                        vals.forEachIndexed { index, v ->
                            val isSelected = selectedIndex == index
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight((if (inverted) (max - v) / range else (v - min) / range).coerceIn(0.05f, 1f))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                                    )
                                    .clickable { 
                                        selectedIndex = if (selectedIndex == index) null else index
                                    }
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        Text("Lap Distance", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun RouteMap(geoJson: Any?, modifier: Modifier = Modifier.height(150.dp)) {
    val points = remember(geoJson) { parseGeoJson(geoJson) }
    
    if (points.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().background(Color.LightGray, RoundedCornerShape(8.dp)), Alignment.Center) {
            Text("No GPS")
        }
    } else {
        val cameraPositionState = rememberCameraPositionState()
        
        // Use a flag to ensure we only zoom once per set of points
        var initialZoomDone by remember(points) { mutableStateOf(false) }

        GoogleMap(
            modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            cameraPositionState = cameraPositionState,
            onMapLoaded = {
                if (!initialZoomDone && points.isNotEmpty()) {
                    val builder = LatLngBounds.builder()
                    points.forEach { builder.include(it) }
                    try {
                        cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(builder.build(), 64))
                        initialZoomDone = true
                    } catch (e: Exception) {
                        // Map might not be fully measured yet in some edge cases
                    }
                }
            },
            googleMapOptionsFactory = { 
                com.google.android.gms.maps.GoogleMapOptions().liteMode(true) 
            }
        ) {
            Polyline(
                points = points,
                color = MaterialTheme.colorScheme.primary,
                width = 10f,
                startCap = RoundCap(),
                endCap = RoundCap()
            )
        }
    }
}

fun decodePolyline(encoded: String): List<LatLng> {
    val poly = ArrayList<LatLng>()
    var index = 0
    val len = encoded.length
    var lat = 0
    var lng = 0
    while (index < len) {
        var b: Int
        var shift = 0
        var result = 0
        do {
            b = encoded[index++].code - 63
            result = result or (b and 0x1f shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lat += dlat
        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or (b and 0x1f shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lng += dlng
        poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
    }
    return poly
}

fun parseGeoJson(json: Any?): List<LatLng> {
    if (json is String) return decodePolyline(json)
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

fun formatUtcToLocal(utcString: String, includeTime: Boolean = true): String {
    return try {
        // Handle various ISO 8601 formats including those with nanoseconds from Rust
        val cleaned = if (utcString.contains(".")) {
            val parts = utcString.split(".")
            parts[0] + "Z" // Strip fractional seconds for simpler parsing
        } else {
            utcString
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val date = sdf.parse(cleaned) ?: return utcString

        val pattern = if (includeTime) "MMM dd, yyyy HH:mm" else "MMM dd, yyyy"
        val localSdf = SimpleDateFormat(pattern, Locale.getDefault())
        localSdf.format(date)
    } catch (e: Exception) {
        utcString.substringBefore("T")
    }
}

fun shareActivity(context: Context, activity: ActivityFeedItem) {
    val distKm = (activity.distance_meters ?: 0) / 1000.0
    val duration = formatSecondsToHHMMSS(activity.duration_seconds)
    val pace = if (distKm > 0 && activity.duration_seconds != null) {
        val totalSeconds = activity.duration_seconds
        val paceSeconds = (totalSeconds / distKm).toInt()
        "%d:%02d".format(paceSeconds / 60, paceSeconds % 60)
    } else "--"

    val summary = """
        🏃 Gongbus Run Summary
        👤 Runner: ${activity.username ?: "Unknown"}
        📅 Date: ${formatUtcToLocal(activity.start_time, includeTime = false)}
        📝 Title: ${activity.title ?: "Morning Run"}
        
        📏 Distance: %.2f km
        ⏱️ Duration: $duration
        ⚡ Pace: $pace min/km
        💓 Avg HR: ${activity.avg_heart_rate ?: "--"} bpm
        
        Check it out on Gongbus!
    """.trimIndent().format(distKm)

    val sendIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, summary)
        type = "text/plain"
    }

    val shareIntent = Intent.createChooser(sendIntent, "Share Run Summary")
    context.startActivity(shareIntent)
}
