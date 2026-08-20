use axum::{
    extract::{Multipart, Path, Query, State, DefaultBodyLimit},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use fitparser::Value;
use serde::{Deserialize, Serialize};
use sqlx::postgres::{PgPool, PgPoolOptions, PgConnectOptions};
use sqlx::{FromRow, Row, ConnectOptions};
use std::io::Cursor;
use std::net::SocketAddr;
use tower_http::cors::CorsLayer;
use tracing::{info, error};
use futures_util::StreamExt;
use reqwest::multipart as req_multipart;
use sha1::{Sha1, Digest};

#[derive(Clone)]
struct AppState {
    db: PgPool,
    cloudinary_config: CloudinaryConfig,
    strava_config: StravaConfig,
}

impl AppState {
    async fn get_db(&self) -> Result<PgPool, (StatusCode, String)> {
        Ok(self.db.clone())
    }
}

#[derive(Clone)]
struct CloudinaryConfig {
    cloud_name: String,
    api_key: String,
    api_secret: String,
}

#[derive(Clone)]
struct StravaConfig {
    client_id: String,
    client_secret: String,
    redirect_uri: String,
    webhook_verify_token: String,
}

#[derive(Serialize, Deserialize, FromRow)]
struct ActivityFeedItem {
    id: i32,
    user_id: i32,
    strava_id: Option<i64>,
    title: Option<String>,
    start_time: chrono::DateTime<chrono::Utc>,
    distance_meters: Option<i32>,
    duration_seconds: Option<i32>,
    route_line_geojson: Option<serde_json::Value>,
    username: Option<String>,
    avatar_url: Option<String>,
    avg_heart_rate: Option<i32>,
    avg_cadence: Option<i32>,
    total_calories: Option<i32>,
    like_count: i64,
    comment_count: i64,
}

#[derive(Serialize, Deserialize, Clone)]
struct MetricRecord {
    timestamp: Option<chrono::DateTime<chrono::Utc>>,
    heart_rate: Option<u16>,
    cadence: Option<u16>,
    altitude: Option<f64>,
    ground_contact_time: Option<f64>,
    stride_distance: Option<f64>,
    speed: Option<f64>,
    distance: Option<f64>,
}

#[derive(Serialize, Deserialize, FromRow)]
struct Comment {
    username: String,
    avatar_url: Option<String>,
    comment_text: String,
    created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize, Deserialize)]
struct ActivityDetail {
    id: i32,
    user_id: i32,
    strava_id: Option<i64>,
    title: Option<String>,
    start_time: chrono::DateTime<chrono::Utc>,
    distance_meters: Option<i32>,
    duration_seconds: Option<i32>,
    route_line_geojson: Option<serde_json::Value>,
    time_series_data: Option<serde_json::Value>,
    username: String,
    avatar_url: Option<String>,
    avg_heart_rate: Option<i32>,
    max_heart_rate: Option<i32>,
    avg_cadence: Option<i32>,
    total_calories: Option<i32>,
    comments: Vec<Comment>,
}

#[derive(Deserialize)]
struct FeedQuery {
    page: Option<u32>,
    per_page: Option<u32>,
    user_id: Option<i32>,
}

#[derive(Serialize, FromRow)]
struct WeeklyMileage {
    week_start: chrono::NaiveDate,
    distance_meters: i64,
}

#[derive(Serialize)]
struct Dashboard {
    weekly_total_meters: i64,
    monthly_total_meters: i64,
    weekly_trend: Vec<WeeklyMileage>,
    leaderboard: Vec<LeaderboardEntry>,
    activities: Vec<ActivityFeedItem>,
}

#[derive(Serialize, FromRow)]
struct LeaderboardEntry {
    user_id: i32,
    username: Option<String>,
    avatar_url: Option<String>,
    total_meters: i64,
}

#[derive(Serialize, Deserialize, FromRow)]
struct UserProfile {
    id: i32,
    username: String,
    avatar_url: Option<String>,
    marathon_goal_sec: Option<i32>,
    weekly_target_km: Option<f64>,
    monthly_target_km: Option<f64>,
    target_lsd_count: Option<i32>,
    target_race: Option<String>,
    race_date: Option<chrono::NaiveDate>,
    strava_athlete_id: Option<i64>,
}

#[derive(Deserialize)]
struct CommentPayload {
    user_id: i32,
    comment_text: String,
}

#[derive(Deserialize)]
struct LikePayload {
    user_id: i32,
}

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt::init();

    let port = std::env::var("PORT").unwrap_or_else(|_| "3000".to_string()).parse().unwrap();
    let db_url = std::env::var("DATABASE_URL").expect("DATABASE_URL must be set");

    let cloudinary_config = CloudinaryConfig {
        cloud_name: std::env::var("CLOUDINARY_CLOUD_NAME").unwrap_or_default(),
        api_key: std::env::var("CLOUDINARY_API_KEY").unwrap_or_default(),
        api_secret: std::env::var("CLOUDINARY_API_SECRET").unwrap_or_default(),
    };

    let strava_config = StravaConfig {
        client_id: std::env::var("STRAVA_CLIENT_ID").unwrap_or_default(),
        client_secret: std::env::var("STRAVA_CLIENT_SECRET").unwrap_or_default(),
        redirect_uri: std::env::var("STRAVA_REDIRECT_URI").unwrap_or_else(|_| "https://gongbus-api.onrender.com/strava/callback".to_string()),
        webhook_verify_token: std::env::var("STRAVA_WEBHOOK_VERIFY_TOKEN").unwrap_or_else(|_| "gongbus_secret".to_string()),
    };

    let opt: PgConnectOptions = db_url.parse().expect("Invalid DATABASE_URL");
    let opt = opt.disable_statement_logging()
        .statement_cache_capacity(0);

    let pool = PgPoolOptions::new()
        .max_connections(5)
        .acquire_timeout(std::time::Duration::from_secs(30))
        .connect_with(opt)
        .await
        .expect("Failed to create database pool");

    let state = AppState { db: pool, cloudinary_config, strava_config };

    let app = Router::new()
        .route("/feed", get(get_feed))
        .route("/activities", post(sync_activity))
        .route("/activities/:id", get(get_activity).put(update_activity).delete(delete_activity))
        .route("/activities/:id/like", post(like_activity))
        .route("/activities/:id/comment", post(comment_activity))
        .route("/users", get(get_users).post(create_user))
        .route("/users/:id/dashboard", get(get_dashboard))
        .route("/users/:id", get(get_user_profile).put(update_user_profile))
        .route("/users/:id/strava-link", post(get_strava_link))
        .route("/users/:id/strava-sync", post(trigger_strava_sync))
        .route("/strava/callback", get(strava_callback))
        .route("/upload-run", post(upload_run))
        .route("/upload-avatar", post(upload_avatar))
        .route("/webhooks/strava", get(verify_strava_webhook).post(handle_strava_webhook))
        .layer(DefaultBodyLimit::disable())
        .layer(tower_http::trace::TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("Server starting on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn create_user(State(state): State<AppState>, Json(payload): Json<UserProfile>) -> Result<Json<UserProfile>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let row = sqlx::query("INSERT INTO users (username, avatar_url, marathon_goal_sec, weekly_target_km, monthly_target_km, target_lsd_count, target_race, race_date) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id")
        .bind(&payload.username).bind(&payload.avatar_url).bind(payload.marathon_goal_sec).bind(payload.weekly_target_km).bind(payload.monthly_target_km).bind(payload.target_lsd_count).bind(&payload.target_race).bind(payload.race_date)
        .fetch_one(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let id: i32 = row.get(0);
    Ok(Json(UserProfile { id, ..payload }))
}

async fn get_users(State(state): State<AppState>) -> Result<Json<Vec<UserProfile>>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let users = sqlx::query_as::<_, UserProfile>("SELECT id, username, avatar_url, marathon_goal_sec, weekly_target_km, monthly_target_km, target_lsd_count, target_race, race_date, strava_athlete_id FROM users ORDER BY id DESC")
        .fetch_all(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(users))
}

async fn get_user_profile(State(state): State<AppState>, Path(id): Path<i32>) -> Result<Json<UserProfile>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let user = sqlx::query_as::<_, UserProfile>("SELECT id, username, avatar_url, marathon_goal_sec, weekly_target_km, monthly_target_km, target_lsd_count, target_race, race_date, strava_athlete_id FROM users WHERE id = $1")
        .bind(id)
        .fetch_optional(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .ok_or((StatusCode::NOT_FOUND, "User not found".to_string()))?;
    Ok(Json(user))
}

async fn update_user_profile(State(state): State<AppState>, Path(id): Path<i32>, Json(payload): Json<UserProfile>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    sqlx::query("UPDATE users SET username = $1, avatar_url = $2, marathon_goal_sec = $3, weekly_target_km = $4, monthly_target_km = $5, target_lsd_count = $6, target_race = $7, race_date = $8, strava_athlete_id = $9 WHERE id = $10")
        .bind(payload.username).bind(payload.avatar_url).bind(payload.marathon_goal_sec).bind(payload.weekly_target_km).bind(payload.monthly_target_km).bind(payload.target_lsd_count).bind(&payload.target_race).bind(payload.race_date).bind(payload.strava_athlete_id).bind(id)
        .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::OK)
}

async fn get_strava_link(State(state): State<AppState>, Path(id): Path<i32>) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let url = format!(
        "https://www.strava.com/oauth/authorize?client_id={}&redirect_uri={}&response_type=code&scope=read,activity:read_all&state={}",
        state.strava_config.client_id,
        state.strava_config.redirect_uri,
        id
    );
    Ok(Json(serde_json::json!({ "url": url })))
}

async fn fetch_strava_time_series(activity_id: i64, access_token: &str, start_time: chrono::DateTime<chrono::Utc>) -> Option<serde_json::Value> {
    let client = reqwest::Client::new();
    let url = format!("https://www.strava.com/api/v3/activities/{}/streams", activity_id);
    let response = client.get(url)
        .query(&[("keys", "time,distance,altitude,heartrate,cadence,velocity_smooth"), ("key_by_type", "true")])
        .bearer_auth(access_token)
        .send()
        .await
        .ok()?;

    if !response.status().is_success() { return None; }

    let streams: serde_json::Value = response.json().await.ok()?;

    let times = streams.get("time").and_then(|s| s.get("data")).and_then(|d| d.as_array())?;
    let distances = streams.get("distance").and_then(|s| s.get("data")).and_then(|d| d.as_array());
    let altitudes = streams.get("altitude").and_then(|s| s.get("data")).and_then(|d| d.as_array());
    let heartrates = streams.get("heartrate").and_then(|s| s.get("data")).and_then(|d| d.as_array());
    let cadences = streams.get("cadence").and_then(|s| s.get("data")).and_then(|d| d.as_array());
    let velocities = streams.get("velocity_smooth").and_then(|s| s.get("data")).and_then(|d| d.as_array());

    let mut records = Vec::new();
    for i in 0..times.len() {
        let offset_sec = times[i].as_i64().unwrap_or(0);
        let record = MetricRecord {
            timestamp: Some(start_time + chrono::Duration::seconds(offset_sec)),
            heart_rate: heartrates.and_then(|s| s.get(i)).and_then(|v| v.as_u64()).map(|v| v as u16),
            cadence: cadences.and_then(|s| s.get(i)).and_then(|v| v.as_u64()).map(|v| v as u16),
            altitude: altitudes.and_then(|s| s.get(i)).and_then(|v| v.as_f64()),
            speed: velocities.and_then(|s| s.get(i)).and_then(|v| v.as_f64()),
            distance: distances.and_then(|s| s.get(i)).and_then(|v| v.as_f64()),
            ground_contact_time: None,
            stride_distance: None,
        };
        records.push(record);
    }

    serde_json::to_value(records).ok()
}

async fn trigger_strava_sync(State(state): State<AppState>, Path(user_id): Path<i32>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;

    let access_token = get_strava_access_token(&db, &state.strava_config, user_id).await.map_err(|e| {
        error!("Failed to get Strava access token for user {}: {:?}", user_id, e);
        e
    })?;

    info!("Starting manual Strava sync for user {}", user_id);
    let client = reqwest::Client::new();
    let response = client.get("https://www.strava.com/api/v3/athlete/activities")
        .query(&[("per_page", "20")])
        .bearer_auth(&access_token)
        .send()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let activities: Vec<serde_json::Value> = response.json().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    info!("Found {} activities in Strava list. checking for new data...", activities.len());
    for act in activities {
        if let Some(activity_id) = act.get("id").and_then(|v| v.as_i64()) {
            let start_time_str = act.get("start_date").and_then(|v| v.as_str()).unwrap_or_default();
            let start_time = chrono::DateTime::parse_from_rfc3339(start_time_str).map(|dt| dt.with_timezone(&chrono::Utc)).unwrap_or_else(|_| chrono::Utc::now());

            // OPTIMIZATION: Check if we already have detailed time_series_data for this activity
            let existing = sqlx::query("SELECT id FROM activities WHERE user_id = $1 AND start_time = $2 AND time_series_data IS NOT NULL")
                .bind(user_id).bind(start_time)
                .fetch_optional(&db).await.ok().flatten();

            if existing.is_some() {
                info!("Skipping stream fetch for activity {} (already synced with high-res data)", activity_id);
                continue;
            }

            let title = act.get("name").and_then(|v| v.as_str()).unwrap_or("Strava Run").to_string();
            let distance = act.get("distance").and_then(|v| v.as_f64()).unwrap_or(0.0) as i32;
            let duration = act.get("moving_time").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let avg_hr = act.get("average_heartrate").and_then(|v| v.as_f64()).map(|v| v as i32);
            let max_hr = act.get("max_heartrate").and_then(|v| v.as_f64()).map(|v| v as i32);
            let avg_cad = act.get("average_cadence").and_then(|v| v.as_f64()).map(|v| (v * 2.0) as i32);
            let calories = act.get("calories").and_then(|v| v.as_f64()).map(|v| v as i32);

            let route_wkt = if let Some(polyline_str) = act.get("map").and_then(|m| m.get("summary_polyline")).and_then(|v| v.as_str()) {
                if let Ok(coords) = polyline::decode_polyline(polyline_str, 5) {
                    let points: Vec<String> = coords.into_iter().map(|p| format!("{} {}", p.x, p.y)).collect();
                    if points.len() >= 2 {
                        Some(format!("LINESTRING({})", points.join(",")))
                    } else { None }
                } else { None }
            } else { None };

            // Fetch detailed streams for charts
            let ts_data = fetch_strava_time_series(activity_id, &access_token, start_time).await;

            sqlx::query(
                "INSERT INTO activities (user_id, title, start_time, distance_meters, duration_seconds, route_line, time_series_data, avg_heart_rate, max_heart_rate, avg_cadence, total_calories, strava_id)
                 VALUES ($1, $2, $3, $4, $5, ST_GeomFromText($6, 4326), $7, $8, $9, $10, $11, $12)
                 ON CONFLICT (user_id, start_time) DO UPDATE SET
                 title=EXCLUDED.title, distance_meters=EXCLUDED.distance_meters, duration_seconds=EXCLUDED.duration_seconds,
                 route_line=EXCLUDED.route_line, time_series_data=EXCLUDED.time_series_data, avg_heart_rate=EXCLUDED.avg_heart_rate,
                 max_heart_rate=EXCLUDED.max_heart_rate, avg_cadence=EXCLUDED.avg_cadence, total_calories=EXCLUDED.total_calories,
                 strava_id=EXCLUDED.strava_id"
            )
            .bind(user_id).bind(title).bind(start_time).bind(distance).bind(duration).bind(route_wkt).bind(ts_data).bind(avg_hr).bind(max_hr).bind(avg_cad).bind(calories).bind(activity_id)
            .execute(&db).await.ok();
        }
    }

    Ok(StatusCode::OK)
}

#[derive(Deserialize)]
struct StravaCallbackQuery {
    code: String,
    state: String,
}

async fn strava_callback(State(state): State<AppState>, Query(query): Query<StravaCallbackQuery>) -> Result<axum::response::Html<String>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let user_id: i32 = query.state.parse().map_err(|_| (StatusCode::BAD_REQUEST, "Invalid state".to_string()))?;

    let client = reqwest::Client::new();
    let response = client.post("https://www.strava.com/oauth/token")
        .form(&[
            ("client_id", &state.strava_config.client_id),
            ("client_secret", &state.strava_config.client_secret),
            ("code", &query.code),
            ("grant_type", &"authorization_code".to_string()),
        ])
        .send()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let token_data: serde_json::Value = response.json().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let athlete_id = token_data.get("athlete").and_then(|a| a.get("id")).and_then(|v| v.as_i64()).ok_or((StatusCode::INTERNAL_SERVER_ERROR, "No athlete id".to_string()))?;
    let access_token = token_data.get("access_token").and_then(|v| v.as_str()).ok_or((StatusCode::INTERNAL_SERVER_ERROR, "No access token".to_string()))?;
    let refresh_token = token_data.get("refresh_token").and_then(|v| v.as_str()).ok_or((StatusCode::INTERNAL_SERVER_ERROR, "No refresh token".to_string()))?;
    let expires_at = token_data.get("expires_at").and_then(|v| v.as_i64()).ok_or((StatusCode::INTERNAL_SERVER_ERROR, "No expires_at".to_string()))?;
    let expires_at_dt = chrono::DateTime::<chrono::Utc>::from_timestamp(expires_at, 0).unwrap_or_else(|| chrono::Utc::now());

    sqlx::query("UPDATE users SET strava_athlete_id = $1, strava_access_token = $2, strava_refresh_token = $3, strava_token_expires_at = $4 WHERE id = $5")
        .bind(athlete_id).bind(access_token).bind(refresh_token).bind(expires_at_dt).bind(user_id)
        .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(axum::response::Html("<h1>Strava linked successfully! You can close this window.</h1>".to_string()))
}

#[derive(Deserialize)]
struct StravaWebhookVerify {
    #[serde(rename = "hub.mode")]
    mode: String,
    #[serde(rename = "hub.challenge")]
    challenge: String,
    #[serde(rename = "hub.verify_token")]
    verify_token: String,
}

async fn verify_strava_webhook(State(state): State<AppState>, Query(query): Query<StravaWebhookVerify>) -> Result<Json<serde_json::Value>, StatusCode> {
    if query.mode == "subscribe" && query.verify_token == state.strava_config.webhook_verify_token {
        Ok(Json(serde_json::json!({ "hub.challenge": query.challenge })))
    } else {
        Err(StatusCode::FORBIDDEN)
    }
}

async fn get_strava_access_token(db: &PgPool, config: &StravaConfig, user_id: i32) -> Result<String, (StatusCode, String)> {
    let row = sqlx::query("SELECT strava_access_token, strava_refresh_token, strava_token_expires_at FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_one(db).await.map_err(|e| {
            error!("Failed to fetch user for Strava token: {}", e);
            (StatusCode::INTERNAL_SERVER_ERROR, "User not found".to_string())
        })?;

    let access_token: Option<String> = row.get(0);
    let refresh_token: Option<String> = row.get(1);
    let expires_at: Option<chrono::DateTime<chrono::Utc>> = row.get(2);

    if access_token.is_none() || refresh_token.is_none() || expires_at.is_none() {
        return Err((StatusCode::BAD_REQUEST, "Strava account not linked".to_string()));
    }

    let access_token = access_token.unwrap();
    let refresh_token = refresh_token.unwrap();
    let expires_at = expires_at.unwrap();

    // Refresh if expiring in less than 5 minutes
    if expires_at < chrono::Utc::now() + chrono::Duration::minutes(5) {
        info!("Refreshing Strava token for user {}", user_id);
        let client = reqwest::Client::new();
        let response = client.post("https://www.strava.com/oauth/token")
            .form(&[
                ("client_id", &config.client_id),
                ("client_secret", &config.client_secret),
                ("refresh_token", &refresh_token),
                ("grant_type", &"refresh_token".to_string()),
            ])
            .send()
            .await
            .map_err(|e| {
                error!("Strava refresh request failed: {}", e);
                (StatusCode::INTERNAL_SERVER_ERROR, format!("Refresh request failed: {}", e))
            })?;

        let token_data: serde_json::Value = response.json().await.map_err(|e| {
            error!("Failed to parse Strava refresh response: {}", e);
            (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to parse refresh response: {}", e))
        })?;

        let new_access_token = token_data.get("access_token").and_then(|v| v.as_str()).ok_or_else(|| {
            error!("No access_token in refresh response: {:?}", token_data);
            (StatusCode::INTERNAL_SERVER_ERROR, "No new access token".to_string())
        })?;
        let new_refresh_token = token_data.get("refresh_token").and_then(|v| v.as_str()).ok_or_else(|| {
            error!("No refresh_token in refresh response");
            (StatusCode::INTERNAL_SERVER_ERROR, "No new refresh token".to_string())
        })?;
        let new_expires_at = token_data.get("expires_at").and_then(|v| v.as_i64()).ok_or_else(|| {
            error!("No expires_at in refresh response");
            (StatusCode::INTERNAL_SERVER_ERROR, "No new expires_at".to_string())
        })?;
        let new_expires_at_dt = chrono::DateTime::<chrono::Utc>::from_timestamp(new_expires_at, 0).unwrap_or_else(|| chrono::Utc::now());

        sqlx::query("UPDATE users SET strava_access_token = $1, strava_refresh_token = $2, strava_token_expires_at = $3 WHERE id = $4")
            .bind(new_access_token).bind(new_refresh_token).bind(new_expires_at_dt).bind(user_id)
            .execute(db).await.map_err(|e| {
                error!("Failed to update Strava tokens in DB: {}", e);
                (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
            })?;

        Ok(new_access_token.to_string())
    } else {
        Ok(access_token)
    }
}

async fn handle_strava_webhook(State(state): State<AppState>, Json(payload): Json<serde_json::Value>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;

    let object_type = payload.get("object_type").and_then(|v| v.as_str());
    let aspect_type = payload.get("aspect_type").and_then(|v| v.as_str());

    if object_type == Some("activity") && aspect_type == Some("create") {
        if let (Some(activity_id), Some(owner_id)) = (
            payload.get("object_id").and_then(|v| v.as_i64()),
            payload.get("owner_id").and_then(|v| v.as_i64())
        ) {
            // Find user by strava_athlete_id
            let user_row = sqlx::query("SELECT id FROM users WHERE strava_athlete_id = $1")
                .bind(owner_id)
                .fetch_optional(&db).await.ok().flatten();

            if let Some(row) = user_row {
                let user_id: i32 = row.get(0);

                // Get a valid access token (refreshes if needed)
                let access_token = get_strava_access_token(&db, &state.strava_config, user_id).await?;

                // Pull activity details from Strava
                let client = reqwest::Client::new();
                let response = client.get(format!("https://www.strava.com/api/v3/activities/{}", activity_id))
                    .bearer_auth(&access_token)
                    .send()
                    .await;

                if let Ok(resp) = response {
                    if let Ok(act) = resp.json::<serde_json::Value>().await {
                        let title = act.get("name").and_then(|v| v.as_str()).unwrap_or("Strava Run").to_string();
                        let start_time_str = act.get("start_date").and_then(|v| v.as_str()).unwrap_or_default();
                        let start_time = chrono::DateTime::parse_from_rfc3339(start_time_str).map(|dt| dt.with_timezone(&chrono::Utc)).unwrap_or_else(|_| chrono::Utc::now());
                        let distance = act.get("distance").and_then(|v| v.as_f64()).unwrap_or(0.0) as i32;
                        let duration = act.get("moving_time").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
                        let avg_hr = act.get("average_heartrate").and_then(|v| v.as_f64()).map(|v| v as i32);
                        let max_hr = act.get("max_heartrate").and_then(|v| v.as_f64()).map(|v| v as i32);
                        let avg_cad = act.get("average_cadence").and_then(|v| v.as_f64()).map(|v| (v * 2.0) as i32);
                        let calories = act.get("calories").and_then(|v| v.as_f64()).map(|v| v as i32);

                        // Strava polyline
                        let route_wkt = if let Some(polyline_str) = act.get("map").and_then(|m| m.get("summary_polyline")).and_then(|v| v.as_str()) {
                            if let Ok(coords) = polyline::decode_polyline(polyline_str, 5) {
                                let points: Vec<String> = coords.into_iter().map(|p| format!("{} {}", p.x, p.y)).collect();
                                if points.len() >= 2 {
                                    Some(format!("LINESTRING({})", points.join(",")))
                                } else { None }
                            } else { None }
                        } else { None };

                        // Fetch detailed streams for charts
                        let ts_data = fetch_strava_time_series(activity_id, &access_token, start_time).await;

                        sqlx::query(
                            "INSERT INTO activities (user_id, title, start_time, distance_meters, duration_seconds, route_line, time_series_data, avg_heart_rate, max_heart_rate, avg_cadence, total_calories, strava_id)
                             VALUES ($1, $2, $3, $4, $5, ST_GeomFromText($6, 4326), $7, $8, $9, $10, $11, $12)
                             ON CONFLICT (user_id, start_time) DO UPDATE SET
                             title=EXCLUDED.title, distance_meters=EXCLUDED.distance_meters, duration_seconds=EXCLUDED.duration_seconds,
                             route_line=EXCLUDED.route_line, time_series_data=EXCLUDED.time_series_data, avg_heart_rate=EXCLUDED.avg_heart_rate,
                             max_heart_rate=EXCLUDED.max_heart_rate, avg_cadence=EXCLUDED.avg_cadence, total_calories=EXCLUDED.total_calories,
                             strava_id=EXCLUDED.strava_id"
                        )
                        .bind(user_id).bind(title).bind(start_time).bind(distance).bind(duration).bind(route_wkt).bind(ts_data).bind(avg_hr).bind(max_hr).bind(avg_cad).bind(calories).bind(activity_id)
                        .execute(&db).await.ok();
                    }
                }
            }
        }
    }

    Ok(StatusCode::OK)
}

async fn upload_avatar(State(state): State<AppState>, mut multipart: Multipart) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let mut data = Vec::new();
    while let Some(field) = multipart.next_field().await.map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))? {
        if field.name() == Some("file") {
            data = field.bytes().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?.to_vec();
        }
    }
    if data.is_empty() { return Err((StatusCode::BAD_REQUEST, "No file".to_string())); }
    let timestamp = chrono::Utc::now().timestamp().to_string();
    let signature_string = format!("timestamp={}{}", timestamp, state.cloudinary_config.api_secret);
    let mut hasher = Sha1::new();
    hasher.update(signature_string.as_bytes());
    let signature = format!("{:x}", hasher.finalize());
    let client = reqwest::Client::new();
    let form = req_multipart::Form::new().text("timestamp", timestamp).text("api_key", state.cloudinary_config.api_key.clone()).text("signature", signature).part("file", req_multipart::Part::bytes(data).file_name("avatar.jpg"));
    let url = format!("https://api.cloudinary.com/v1_1/{}/image/upload", state.cloudinary_config.cloud_name);
    let response = client.post(url).multipart(form).send().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let json: serde_json::Value = response.json().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    if let Some(secure_url) = json.get("secure_url").and_then(|v| v.as_str()) { Ok(Json(serde_json::json!({ "url": secure_url }))) }
    else { Err((StatusCode::INTERNAL_SERVER_ERROR, "Cloudinary upload failed".to_string())) }
}

async fn upload_run(State(state): State<AppState>, mut multipart: Multipart) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    let mut data = Vec::new();
    let mut user_id = 1;
    let mut title = None;

    while let Some(mut field) = multipart.next_field().await.map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))? {
        if field.name() == Some("file") {
            while let Some(chunk) = field.next().await {
                let bytes = chunk.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
                data.extend_from_slice(&bytes);
            }
        } else if field.name() == Some("user_id") {
            user_id = field.text().await.unwrap_or_default().parse().unwrap_or(1);
        } else if field.name() == Some("title") {
            let t = field.text().await.unwrap_or_default();
            if !t.is_empty() {
                title = Some(t);
            }
        }
    }
    let mut reader = Cursor::new(data);
    let fit_data = fitparser::from_reader(&mut reader).map_err(|e| (StatusCode::UNPROCESSABLE_ENTITY, e.to_string()))?;
    let mut start_time = chrono::Utc::now();
    let mut final_distance = 0.0;
    let mut final_duration = 0;
    let mut avg_hr = None;
    let mut max_hr = None;
    let mut avg_cad = None;
    let mut calories = None;
    let mut coordinates = Vec::new();
    let mut time_series_with_meta = Vec::new();
    for record in fit_data {
        match record.kind() {
            fitparser::profile::MesgNum::Session => {
                for field in record.fields() {
                    match field.name() {
                        "start_time" => if let Value::Timestamp(ts) = field.value() { start_time = (*ts).into(); }
                        "total_distance" => if let Value::Float64(d) = field.value() { final_distance = *d; }
                        "total_timer_time" => if let Value::Float64(t) = field.value() { final_duration = *t as i32; }
                        "avg_heart_rate" => if let Value::UInt8(hr) = field.value() { avg_hr = Some(*hr as i32); }
                        "max_heart_rate" => if let Value::UInt8(hr) = field.value() { max_hr = Some(*hr as i32); }
                        "avg_running_cadence" | "avg_cadence" => if let Value::UInt8(c) = field.value() { avg_cad = Some((*c as i32) * 2); }
                        "total_calories" => if let Value::UInt16(cal) = field.value() { calories = Some(*cal as i32); }
                        _ => {}
                    }
                }
            }
            fitparser::profile::MesgNum::Record => {
                let mut lat = None;
                let mut lon = None;
                let mut metric = MetricRecord { timestamp: None, heart_rate: None, cadence: None, altitude: None, ground_contact_time: None, stride_distance: None, speed: None, distance: None };
                for field in record.fields() {
                    match field.name() {
                        "timestamp" => if let Value::Timestamp(ts) = field.value() { metric.timestamp = Some((*ts).into()); }
                        "position_lat" => if let Value::SInt32(sc) = field.value() { lat = Some(*sc as f64 * (180.0 / 2.0_f64.powi(31))); }
                        "position_long" => if let Value::SInt32(sc) = field.value() { lon = Some(*sc as f64 * (180.0 / 2.0_f64.powi(31))); }
                        "heart_rate" => if let Value::UInt8(hr) = field.value() { metric.heart_rate = Some(*hr as u16); }
                        "cadence" => if let Value::UInt8(c) = field.value() { metric.cadence = Some((*c as u16) * 2); }
                        "altitude" | "enhanced_altitude" => if let Value::Float64(a) = field.value() { metric.altitude = Some(*a); }
                        "stance_time" => if let Value::Float64(st) = field.value() { metric.ground_contact_time = Some(*st); }
                        "step_length" => if let Value::Float64(sl) = field.value() { metric.stride_distance = Some(*sl / 1000.0); }
                        "speed" | "enhanced_speed" => if let Value::Float64(s) = field.value() { metric.speed = Some(*s); }
                        "distance" => if let Value::Float64(d) = field.value() { metric.distance = Some(*d); }
                        _ => {}
                    }
                }
                let has_gps = lat.is_some() && lon.is_some();
                if let (Some(la), Some(lo)) = (lat, lon) { coordinates.push(format!("{} {}", lo, la)); }
                if metric.timestamp.is_some() { time_series_with_meta.push((metric, has_gps)); }
            }
            _ => {}
        }
    }
    let initial_running_idx = time_series_with_meta.iter().position(|(r, _)| r.cadence.unwrap_or(0) >= 10).unwrap_or(0);
    let stabilized_idx = (initial_running_idx + 5).min(time_series_with_meta.len() - 1);
    if stabilized_idx > 0 && stabilized_idx < time_series_with_meta.len() {
        let (ref start_rec, _) = time_series_with_meta[stabilized_idx];
        if let Some(ts) = start_rec.timestamp { start_time = ts; }
        let start_dist = start_rec.distance.unwrap_or(0.0);
        let last_rec = time_series_with_meta.last().unwrap();
        let end_dist = last_rec.0.distance.unwrap_or(final_distance);
        final_distance = end_dist - start_dist;
        let subset = &time_series_with_meta[stabilized_idx..];
        final_duration = subset.len() as i32;
        let active_subset: Vec<&MetricRecord> = subset.iter().map(|(r, _)| r).filter(|r| r.cadence.unwrap_or(0) >= 10 && r.speed.unwrap_or(0.0) > 0.5).collect();
        if !active_subset.is_empty() {
            let hr_v: Vec<u16> = active_subset.iter().filter_map(|r| r.heart_rate).collect();
            if !hr_v.is_empty() { avg_hr = Some((hr_v.iter().map(|&v| v as i32).sum::<i32>()) / hr_v.len() as i32); }
            let cad_v: Vec<u16> = active_subset.iter().filter_map(|r| r.cadence).collect();
            if !cad_v.is_empty() { avg_cad = Some((cad_v.iter().map(|&v| v as i32).sum::<i32>()) / cad_v.len() as i32); }
        }
    }
    if coordinates.len() < 2 { return Err((StatusCode::UNPROCESSABLE_ENTITY, "Insufficient GPS".to_string())); }
    let wkt = format!("LINESTRING({})", coordinates.join(","));
    let ts_final: Vec<MetricRecord> = time_series_with_meta.into_iter().map(|(r, _)| r).collect();
    let ts_json = serde_json::to_value(ts_final).unwrap_or(serde_json::Value::Null);

    let final_title = title.unwrap_or_else(|| "Morning Run".to_string());

    sqlx::query("INSERT INTO activities (user_id, title, start_time, distance_meters, duration_seconds, route_line, time_series_data, avg_heart_rate, max_heart_rate, avg_cadence, total_calories, strava_id) VALUES ($1, $2, $3, $4, $5, ST_GeomFromText($6, 4326), $7, $8, $9, $10, $11, NULL) ON CONFLICT (user_id, start_time) DO UPDATE SET title=EXCLUDED.title, distance_meters=EXCLUDED.distance_meters, duration_seconds=EXCLUDED.duration_seconds, route_line=EXCLUDED.route_line, time_series_data=EXCLUDED.time_series_data, avg_heart_rate=EXCLUDED.avg_heart_rate, max_heart_rate=EXCLUDED.max_heart_rate, avg_cadence=EXCLUDED.avg_cadence, total_calories=EXCLUDED.total_calories")
        .bind(user_id).bind(final_title).bind(start_time).bind(final_distance as i32).bind(final_duration).bind(wkt).bind(ts_json).bind(avg_hr).bind(max_hr).bind(avg_cad).bind(calories)
        .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::CREATED)
}

async fn get_feed(State(state): State<AppState>, Query(query): Query<FeedQuery>) -> Result<Json<Vec<ActivityFeedItem>>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let per_page = query.per_page.unwrap_or(50) as i64;
    let offset = (query.page.unwrap_or(1) as i64 - 1) * per_page;

    let activities = match query.user_id {
        Some(uid) => {
            sqlx::query_as::<_, ActivityFeedItem>(
                r#"SELECT a.id, a.user_id, a.strava_id, a.title, a.start_time, a.distance_meters, a.duration_seconds,
                   ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, u.username, u.avatar_url,
                   a.avg_heart_rate, a.avg_cadence, a.total_calories,
                   (SELECT COUNT(*) FROM activity_likes l WHERE l.activity_id = a.id) as like_count,
                   (SELECT COUNT(*) FROM activity_comments c WHERE c.activity_id = a.id) as comment_count
                   FROM activities a
                   LEFT JOIN users u ON a.user_id = u.id
                   WHERE a.user_id = $1
                   ORDER BY a.start_time DESC LIMIT $2 OFFSET $3"#
            ).bind(uid).bind(per_page).bind(offset).fetch_all(&db).await
        },
        None => {
            sqlx::query_as::<_, ActivityFeedItem>(
                r#"SELECT a.id, a.user_id, a.strava_id, a.title, a.start_time, a.distance_meters, a.duration_seconds,
                   ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, u.username, u.avatar_url,
                   a.avg_heart_rate, a.avg_cadence, a.total_calories,
                   (SELECT COUNT(*) FROM activity_likes l WHERE l.activity_id = a.id) as like_count,
                   (SELECT COUNT(*) FROM activity_comments c WHERE c.activity_id = a.id) as comment_count
                   FROM activities a
                   LEFT JOIN users u ON a.user_id = u.id
                   ORDER BY a.start_time DESC LIMIT $1 OFFSET $2"#
            ).bind(per_page).bind(offset).fetch_all(&db).await
        }
    }.map_err(|e| {
        error!("Feed query failed: {:?}", e);
        (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    })?;

    Ok(Json(activities))
}

async fn get_activity(State(state): State<AppState>, Path(id): Path<i32>) -> Result<Json<ActivityDetail>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let row = sqlx::query("SELECT a.id, a.user_id, a.strava_id, a.title, a.start_time, a.distance_meters, a.duration_seconds, ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, a.time_series_data, u.username, u.avatar_url, a.avg_heart_rate, a.max_heart_rate, a.avg_cadence, a.total_calories FROM activities a LEFT JOIN users u ON a.user_id = u.id WHERE a.id = $1").bind(id).fetch_optional(&db).await.map_err(|e| {
        error!("Get activity failed: {:?}", e);
        (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    })?.ok_or((StatusCode::NOT_FOUND, "Not found".to_string()))?;

    let comments = sqlx::query_as::<_, Comment>("SELECT u.username, u.avatar_url, c.comment_text, c.created_at FROM activity_comments c JOIN users u ON c.user_id = u.id WHERE c.activity_id = $1 ORDER BY c.created_at ASC").bind(id).fetch_all(&db).await.unwrap_or_default();

    Ok(Json(ActivityDetail {
        id: row.get(0),
        user_id: row.get(1),
        strava_id: row.get(2),
        title: row.get(3),
        start_time: row.get(4),
        distance_meters: row.get(5),
        duration_seconds: row.get(6),
        route_line_geojson: row.get(7),
        time_series_data: row.get(8),
        username: row.get::<Option<String>, _>(9).unwrap_or_else(|| "Unknown".to_string()),
        avatar_url: row.get(10),
        avg_heart_rate: row.get(11),
        max_heart_rate: row.get(12),
        avg_cadence: row.get(13),
        total_calories: row.get(14),
        comments
    }))
}

async fn get_dashboard(State(state): State<AppState>, Path(user_id): Path<i32>) -> Result<Json<Dashboard>, (StatusCode, String)> {
    let db = state.get_db().await?;

    let stats = sqlx::query("SELECT COALESCE(SUM(distance_meters) FILTER (WHERE start_time >= date_trunc('week', now())), 0)::bigint, COALESCE(SUM(distance_meters) FILTER (WHERE start_time >= date_trunc('month', now())), 0)::bigint FROM activities WHERE user_id = $1")
        .bind(user_id)
        .fetch_one(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let weekly_trend = sqlx::query_as::<_, WeeklyMileage>("SELECT date_trunc('week', start_time)::date as week_start, COALESCE(SUM(distance_meters), 0)::bigint as distance_meters FROM activities WHERE user_id = $1 GROUP BY week_start ORDER BY week_start ASC LIMIT 10")
        .bind(user_id)
        .fetch_all(&db).await.unwrap_or_default();

    let leaderboard = sqlx::query_as::<_, LeaderboardEntry>(r#"SELECT u.id as user_id, u.username, u.avatar_url, COALESCE(SUM(a.distance_meters), 0)::bigint as total_meters FROM users u JOIN activities a ON u.id = a.user_id WHERE a.start_time >= date_trunc('month', now()) GROUP BY u.id ORDER BY total_meters DESC LIMIT 10"#)
        .fetch_all(&db).await.unwrap_or_default();

    let activities = sqlx::query_as::<_, ActivityFeedItem>(r#"SELECT a.id, a.user_id, a.strava_id, a.title, a.start_time, a.distance_meters, a.duration_seconds, ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, u.username, u.avatar_url, a.avg_heart_rate, a.avg_cadence, a.total_calories, (SELECT COUNT(*) FROM activity_likes l WHERE l.activity_id = a.id) as like_count, (SELECT COUNT(*) FROM activity_comments c WHERE c.activity_id = a.id) as comment_count FROM activities a LEFT JOIN users u ON a.user_id = u.id WHERE a.user_id = $1 ORDER BY a.start_time DESC"#)
        .bind(user_id)
        .fetch_all(&db).await.unwrap_or_default();

    Ok(Json(Dashboard {
        weekly_total_meters: stats.get(0),
        monthly_total_meters: stats.get(1),
        weekly_trend,
        leaderboard,
        activities
    }))
}

async fn delete_activity(State(state): State<AppState>, Path(id): Path<i32>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    sqlx::query("DELETE FROM activities WHERE id = $1").bind(id).execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn like_activity(State(state): State<AppState>, Path(id): Path<i32>, Json(payload): Json<LikePayload>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    let user_id = payload.user_id;
    let existing = sqlx::query("SELECT id FROM activity_likes WHERE activity_id = $1 AND user_id = $2").bind(id).bind(user_id).fetch_optional(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    if existing.is_some() { sqlx::query("DELETE FROM activity_likes WHERE activity_id = $1 AND user_id = $2").bind(id).bind(user_id).execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?; }
    else { sqlx::query("INSERT INTO activity_likes (activity_id, user_id) VALUES ($1, $2)").bind(id).bind(user_id).execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?; }
    Ok(StatusCode::OK)
}

async fn comment_activity(State(state): State<AppState>, Path(id): Path<i32>, Json(payload): Json<CommentPayload>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    sqlx::query("INSERT INTO activity_comments (activity_id, user_id, comment_text) VALUES ($1, $2, $3)").bind(id).bind(payload.user_id).bind(payload.comment_text).execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::CREATED)
}

async fn update_activity(State(state): State<AppState>, Path(id): Path<i32>, Json(payload): Json<std::collections::HashMap<String, Option<String>>>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    if let Some(Some(title)) = payload.get("title") {
        sqlx::query("UPDATE activities SET title = $1 WHERE id = $2")
            .bind(title)
            .bind(id)
            .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(StatusCode::OK)
}

async fn sync_activity(State(state): State<AppState>, Json(payload): Json<ActivityDetail>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;

    // Convert GeoJSON to WKT for PostGIS if provided
    let route_wkt = if let Some(geojson) = payload.route_line_geojson {
        // Simple conversion for LineString
        let coords = geojson.get("coordinates").and_then(|c| c.as_array());
        if let Some(arr) = coords {
            let points: Vec<String> = arr.iter().filter_map(|p| {
                let p_arr = p.as_array()?;
                Some(format!("{} {}", p_arr[0], p_arr[1]))
            }).collect();
            if points.len() >= 2 {
                Some(format!("LINESTRING({})", points.join(",")))
            } else { None }
        } else { None }
    } else { None };

    sqlx::query(
        r#"INSERT INTO activities
           (user_id, title, start_time, distance_meters, duration_seconds, route_line,
            avg_heart_rate, max_heart_rate, avg_cadence, total_calories, strava_id)
           VALUES ($1, $2, $3, $4, $5, ST_GeomFromText($6, 4326), $7, $8, $9, $10, $11)
           ON CONFLICT (user_id, start_time) DO UPDATE SET
           title = EXCLUDED.title,
           distance_meters = EXCLUDED.distance_meters,
           duration_seconds = EXCLUDED.duration_seconds,
           route_line = EXCLUDED.route_line,
           avg_heart_rate = EXCLUDED.avg_heart_rate,
           max_heart_rate = EXCLUDED.max_heart_rate,
           avg_cadence = EXCLUDED.avg_cadence,
           total_calories = EXCLUDED.total_calories,
           strava_id = EXCLUDED.strava_id"#
    )
    .bind(payload.user_id)
    .bind(&payload.title)
    .bind(payload.start_time)
    .bind(payload.distance_meters)
    .bind(payload.duration_seconds)
    .bind(route_wkt)
    .bind(payload.avg_heart_rate)
    .bind(payload.max_heart_rate)
    .bind(payload.avg_cadence)
    .bind(payload.total_calories)
    .bind(payload.strava_id)
    .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(StatusCode::CREATED)
}
