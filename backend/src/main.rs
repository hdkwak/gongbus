use axum::{
    extract::{Multipart, Path, Query, State, DefaultBodyLimit},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use fitparser::Value;
use serde::{Deserialize, Serialize};
use sqlx::postgres::{PgPool, PgPoolOptions};
use sqlx::{FromRow, Row};
use std::io::Cursor;
use std::net::SocketAddr;
use tower_http::cors::CorsLayer;
use tracing::{info, error};
use futures_util::StreamExt;
use reqwest::multipart as req_multipart;
use sha1::{Sha1, Digest};
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Clone)]
struct AppState {
    db: Arc<RwLock<Option<PgPool>>>,
    cloudinary_config: CloudinaryConfig,
}

impl AppState {
    async fn get_db(&self) -> Result<PgPool, (StatusCode, String)> {
        let db = self.db.read().await;
        db.clone().ok_or((StatusCode::SERVICE_UNAVAILABLE, "Database not ready yet. Please try again in a few seconds.".to_string()))
    }
}

#[derive(Clone)]
struct CloudinaryConfig {
    cloud_name: String,
    api_key: String,
    api_secret: String,
}

#[derive(Serialize, Deserialize, FromRow)]
struct ActivityFeedItem {
    id: i32,
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
struct Pagination {
    page: Option<u32>,
    per_page: Option<u32>,
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

    let db_holder = Arc::new(RwLock::new(None));
    let state = AppState { db: db_holder.clone(), cloudinary_config };

    // Start background database connection
    let db_url_clone = db_url.clone();
    tokio::spawn(async move {
        info!("Connecting to database in background...");
        let mut retry_count = 0;
        loop {
            match PgPoolOptions::new()
                .max_connections(2)
                .acquire_timeout(std::time::Duration::from_secs(60))
                .connect(&db_url_clone)
                .await
            {
                Ok(pool) => {
                    info!("Database connected successfully!");
                    let mut lock = db_holder.write().await;
                    *lock = Some(pool);
                    break;
                }
                Err(e) => {
                    retry_count += 1;
                    error!("Connection attempt {} failed: {}. Details: {:?}. Retrying in 10s...", retry_count, e, e);
                    if !db_url_clone.contains("sslmode=require") {
                        info!("Hint: If you are connecting to a production database (e.g. Render/Neon), ensure 'sslmode=require' is included in your DATABASE_URL.");
                    }
                    tokio::time::sleep(std::time::Duration::from_secs(10)).await;
                }
            }
        }
    });

    let app = Router::new()
        .route("/feed", get(get_feed))
        .route("/activities/:id", get(get_activity).delete(delete_activity))
        .route("/activities/:id/like", post(like_activity))
        .route("/activities/:id/comment", post(comment_activity))
        .route("/users", post(create_user))
        .route("/users/:id/dashboard", get(get_dashboard))
        .route("/users/:id", get(get_user_profile).put(update_user_profile))
        .route("/upload-run", post(upload_run))
        .route("/upload-avatar", post(upload_avatar))
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

async fn get_user_profile(State(state): State<AppState>, Path(id): Path<i32>) -> Result<Json<UserProfile>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let user = sqlx::query_as::<_, UserProfile>("SELECT id, username, avatar_url, marathon_goal_sec, weekly_target_km, monthly_target_km, target_lsd_count, target_race, race_date FROM users WHERE id = $1")
        .bind(id)
        .fetch_optional(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .ok_or((StatusCode::NOT_FOUND, "User not found".to_string()))?;
    Ok(Json(user))
}

async fn update_user_profile(State(state): State<AppState>, Path(id): Path<i32>, Json(payload): Json<UserProfile>) -> Result<StatusCode, (StatusCode, String)> {
    let db = state.get_db().await?;
    sqlx::query("UPDATE users SET username = $1, avatar_url = $2, marathon_goal_sec = $3, weekly_target_km = $4, monthly_target_km = $5, target_lsd_count = $6, target_race = $7, race_date = $8 WHERE id = $9")
        .bind(payload.username).bind(payload.avatar_url).bind(payload.marathon_goal_sec).bind(payload.weekly_target_km).bind(payload.monthly_target_km).bind(payload.target_lsd_count).bind(payload.target_race).bind(payload.race_date).bind(id)
        .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
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
    while let Some(mut field) = multipart.next_field().await.map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))? {
        if field.name() == Some("file") {
            while let Some(chunk) = field.next().await {
                let bytes = chunk.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
                data.extend_from_slice(&bytes);
            }
        } else if field.name() == Some("user_id") {
            user_id = field.text().await.unwrap_or_default().parse().unwrap_or(1);
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
    sqlx::query("INSERT INTO activities (user_id, title, start_time, distance_meters, duration_seconds, route_line, time_series_data, avg_heart_rate, max_heart_rate, avg_cadence, total_calories) VALUES ($1, $2, $3, $4, $5, ST_GeomFromText($6, 4326), $7, $8, $9, $10, $11) ON CONFLICT (user_id, start_time) DO UPDATE SET title=EXCLUDED.title, distance_meters=EXCLUDED.distance_meters, duration_seconds=EXCLUDED.duration_seconds, route_line=EXCLUDED.route_line, time_series_data=EXCLUDED.time_series_data, avg_heart_rate=EXCLUDED.avg_heart_rate, max_heart_rate=EXCLUDED.max_heart_rate, avg_cadence=EXCLUDED.avg_cadence, total_calories=EXCLUDED.total_calories")
        .bind(user_id).bind("Morning Run").bind(start_time).bind(final_distance as i32).bind(final_duration).bind(wkt).bind(ts_json).bind(avg_hr).bind(max_hr).bind(avg_cad).bind(calories)
        .execute(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(StatusCode::CREATED)
}

async fn get_feed(State(state): State<AppState>, Query(pagination): Query<Pagination>) -> Result<Json<Vec<ActivityFeedItem>>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let per_page = pagination.per_page.unwrap_or(20) as i64;
    let offset = (pagination.page.unwrap_or(1) as i64 - 1) * per_page;

    let activities = sqlx::query_as::<_, ActivityFeedItem>(
        r#"SELECT a.id, a.title, a.start_time, a.distance_meters, a.duration_seconds,
           ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, u.username, u.avatar_url,
           a.avg_heart_rate, a.avg_cadence, a.total_calories,
           (SELECT COUNT(*) FROM activity_likes l WHERE l.activity_id = a.id) as like_count,
           (SELECT COUNT(*) FROM activity_comments c WHERE c.activity_id = a.id) as comment_count
           FROM activities a
           LEFT JOIN users u ON a.user_id = u.id
           ORDER BY a.start_time DESC LIMIT $1 OFFSET $2"#
    ).bind(per_page).bind(offset).fetch_all(&db).await.map_err(|e| {
        error!("Feed query failed: {:?}", e);
        (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    })?;

    Ok(Json(activities))
}

async fn get_activity(State(state): State<AppState>, Path(id): Path<i32>) -> Result<Json<ActivityDetail>, (StatusCode, String)> {
    let db = state.get_db().await?;
    let row = sqlx::query("SELECT a.id, a.title, a.start_time, a.distance_meters, a.duration_seconds, ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, a.time_series_data, u.username, u.avatar_url, a.avg_heart_rate, a.max_heart_rate, a.avg_cadence, a.total_calories FROM activities a LEFT JOIN users u ON a.user_id = u.id WHERE a.id = $1").bind(id).fetch_optional(&db).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?.ok_or((StatusCode::NOT_FOUND, "Not found".to_string()))?;
    let comments = sqlx::query_as::<_, Comment>("SELECT u.username, u.avatar_url, c.comment_text, c.created_at FROM activity_comments c JOIN users u ON c.user_id = u.id WHERE c.activity_id = $1 ORDER BY c.created_at ASC").bind(id).fetch_all(&db).await.unwrap_or_default();
    Ok(Json(ActivityDetail {
        id: row.get(0),
        title: row.get(1),
        start_time: row.get(2),
        distance_meters: row.get(3),
        duration_seconds: row.get(4),
        route_line_geojson: row.get(5),
        time_series_data: row.get(6),
        username: row.get::<Option<String>, _>(7).unwrap_or_else(|| "Unknown".to_string()),
        avatar_url: row.get(8),
        avg_heart_rate: row.get(9),
        max_heart_rate: row.get(10),
        avg_cadence: row.get(11),
        total_calories: row.get(12),
        comments
    }))
}

async fn get_dashboard(State(state): State<AppState>, Path(user_id): Path<i32>) -> Json<Dashboard> {
    let db = match state.get_db().await { Ok(d) => d, Err(_) => return Json(Dashboard { weekly_total_meters: 0, monthly_total_meters: 0, weekly_trend: vec![], leaderboard: vec![], activities: vec![] }) };
    let stats = sqlx::query("SELECT COALESCE(SUM(distance_meters) FILTER (WHERE start_time >= date_trunc('week', now())), 0)::bigint, COALESCE(SUM(distance_meters) FILTER (WHERE start_time >= date_trunc('month', now())), 0)::bigint FROM activities WHERE user_id = $1").bind(user_id).fetch_one(&db).await.unwrap();
    let weekly_trend = sqlx::query_as::<_, WeeklyMileage>("SELECT date_trunc('week', start_time)::date as week_start, COALESCE(SUM(distance_meters), 0)::bigint as distance_meters FROM activities WHERE user_id = $1 GROUP BY week_start ORDER BY week_start ASC LIMIT 10").bind(user_id).fetch_all(&db).await.unwrap_or_default();
    let leaderboard = sqlx::query_as::<_, LeaderboardEntry>(r#"SELECT u.username, u.avatar_url, COALESCE(SUM(a.distance_meters), 0)::bigint as total_meters FROM users u JOIN activities a ON u.id = a.user_id WHERE a.start_time >= date_trunc('month', now()) GROUP BY u.id ORDER BY total_meters DESC LIMIT 10"#).fetch_all(&db).await.unwrap_or_default();
    let activities = sqlx::query_as::<_, ActivityFeedItem>(r#"SELECT a.id, a.title, a.start_time, a.distance_meters, a.duration_seconds, ST_AsGeoJSON(a.route_line)::jsonb as route_line_geojson, u.username, u.avatar_url, a.avg_heart_rate, a.avg_cadence, a.total_calories, (SELECT COUNT(*) FROM activity_likes l WHERE l.activity_id = a.id) as like_count, (SELECT COUNT(*) FROM activity_comments c WHERE c.activity_id = a.id) as comment_count FROM activities a LEFT JOIN users u ON a.user_id = u.id WHERE a.user_id = $1 ORDER BY a.start_time DESC"#).bind(user_id).fetch_all(&db).await.unwrap_or_default();
    Json(Dashboard { weekly_total_meters: stats.get(0), monthly_total_meters: stats.get(1), weekly_trend, leaderboard, activities })
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
