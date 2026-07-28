use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::SaltString;
use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier};
use axum::extract::State;
use axum::http::{Request, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::{Deserialize, Serialize};

use crate::state::{MobileSession, WebState};

#[derive(Deserialize)]
pub struct LoginRequest {
    pub password: String,
}

#[derive(Deserialize)]
pub struct ChangePasswordRequest {
    pub old_password: String,
    pub new_password: String,
}

#[derive(Serialize)]
pub struct AuthCheckResponse {
    pub authenticated: bool,
    pub required: bool,
    pub setup_required: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileLoginResponse {
    pub ok: bool,
    pub token: Option<String>,
    pub expires_at: Option<u64>,
}

const MAX_ATTEMPTS: u32 = 5;
const LOCKOUT_SECS: u64 = 60;
const MOBILE_SESSION_TTL_SECS: u64 = 30 * 24 * 60 * 60;

fn prune_expired_mobile_sessions(sessions: &mut HashMap<String, MobileSession>, now: std::time::Instant) {
    sessions.retain(|_, session| session.expires_at > now);
}

fn rotate_sessions_after_password_change(
    browser_sessions: &mut HashSet<String>,
    mobile_sessions: &mut HashMap<String, MobileSession>,
    new_browser_token: String,
) {
    browser_sessions.clear();
    browser_sessions.insert(new_browser_token);
    mobile_sessions.clear();
}

fn session_cookie_path(state: &WebState) -> &str {
    state.public_base_path.as_str()
}

fn api_path_suffix<'a>(path: &'a str, public_base_path: &str) -> Option<&'a str> {
    if let Some(suffix) = path.strip_prefix("/api/") {
        return Some(suffix);
    }
    let base = public_base_path.trim_end_matches('/');
    if base.is_empty() || base == "/" {
        return None;
    }
    path.strip_prefix(base)?.strip_prefix("/api/")
}

fn middleware_api_path_suffix<'a>(path: &'a str, public_base_path: &str) -> Option<&'a str> {
    if let Some(suffix) = api_path_suffix(path, public_base_path) {
        return Some(suffix);
    }

    let base = public_base_path.trim_end_matches('/');
    if !base.is_empty() && base != "/" && path.strip_prefix(base).is_some() {
        return None;
    }

    path.strip_prefix('/').filter(|suffix| !suffix.is_empty())
}

pub async fn login(State(state): State<Arc<WebState>>, Json(body): Json<LoginRequest>) -> Result<Response, StatusCode> {
    let hash_guard = state.password_hash.read().await;
    let hash_str = match hash_guard.as_deref() {
        Some(h) => h.to_string(),
        None => {
            return Ok((StatusCode::OK, Json(serde_json::json!({"ok": true}))).into_response());
        }
    };
    drop(hash_guard);

    // Check rate limit
    {
        let rl = state.login_rate_limit.lock().await;
        if let Some(locked_until) = rl.locked_until {
            if locked_until > std::time::Instant::now() {
                let remaining = (locked_until - std::time::Instant::now()).as_secs();
                return Ok((
                    StatusCode::TOO_MANY_REQUESTS,
                    Json(serde_json::json!({"error": format!("请 {remaining} 秒后再试")})),
                )
                    .into_response());
            }
        }
    }

    let parsed_hash = PasswordHash::new(&hash_str).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    if Argon2::default().verify_password(body.password.as_bytes(), &parsed_hash).is_err() {
        let mut rl = state.login_rate_limit.lock().await;
        rl.fail_count += 1;
        if rl.fail_count >= MAX_ATTEMPTS {
            rl.locked_until = Some(std::time::Instant::now() + std::time::Duration::from_secs(LOCKOUT_SECS));
            rl.fail_count = 0;
        }
        return Err(StatusCode::UNAUTHORIZED);
    }

    // Success — reset rate limit
    {
        let mut rl = state.login_rate_limit.lock().await;
        rl.fail_count = 0;
        rl.locked_until = None;
    }

    let token = uuid::Uuid::new_v4().to_string();
    state.sessions.write().await.insert(token.clone());

    let cookie = format!("dbx_session={token}; Path={}; HttpOnly; SameSite=Lax", session_cookie_path(&state));
    Ok((StatusCode::OK, [("set-cookie", cookie.as_str())], Json(serde_json::json!({"ok": true}))).into_response())
}

pub async fn mobile_login(
    State(state): State<Arc<WebState>>,
    Json(body): Json<LoginRequest>,
) -> Result<Response, StatusCode> {
    if state.password_disabled {
        return Ok(
            (StatusCode::OK, Json(MobileLoginResponse { ok: true, token: None, expires_at: None })).into_response()
        );
    }

    let hash_guard = state.password_hash.read().await;
    let hash_str = match hash_guard.as_deref() {
        Some(hash) => hash.to_string(),
        None => return Err(StatusCode::CONFLICT),
    };
    drop(hash_guard);

    {
        let rate_limit = state.login_rate_limit.lock().await;
        if let Some(locked_until) = rate_limit.locked_until {
            if locked_until > std::time::Instant::now() {
                let remaining = (locked_until - std::time::Instant::now()).as_secs();
                return Ok((
                    StatusCode::TOO_MANY_REQUESTS,
                    Json(serde_json::json!({"error": format!("请 {remaining} 秒后再试")})),
                )
                    .into_response());
            }
        }
    }

    let parsed_hash = PasswordHash::new(&hash_str).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    if Argon2::default().verify_password(body.password.as_bytes(), &parsed_hash).is_err() {
        let mut rate_limit = state.login_rate_limit.lock().await;
        rate_limit.fail_count += 1;
        if rate_limit.fail_count >= MAX_ATTEMPTS {
            rate_limit.locked_until = Some(std::time::Instant::now() + std::time::Duration::from_secs(LOCKOUT_SECS));
            rate_limit.fail_count = 0;
        }
        return Err(StatusCode::UNAUTHORIZED);
    }

    {
        let mut rate_limit = state.login_rate_limit.lock().await;
        rate_limit.fail_count = 0;
        rate_limit.locked_until = None;
    }

    let token = uuid::Uuid::new_v4().to_string();
    let now = std::time::Instant::now();
    let expires_at_instant = now + std::time::Duration::from_secs(MOBILE_SESSION_TTL_SECS);
    {
        let mut sessions = state.mobile_sessions.write().await;
        prune_expired_mobile_sessions(&mut sessions, now);
        sessions.insert(token.clone(), MobileSession { expires_at: expires_at_instant });
    }
    let expires_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        .saturating_add(MOBILE_SESSION_TTL_SECS);

    Ok((StatusCode::OK, Json(MobileLoginResponse { ok: true, token: Some(token), expires_at: Some(expires_at) }))
        .into_response())
}

pub async fn setup(State(state): State<Arc<WebState>>, Json(body): Json<LoginRequest>) -> Result<Response, StatusCode> {
    if state.password_disabled {
        return Err(StatusCode::FORBIDDEN);
    }

    // Only allow setup when no password is configured
    if state.password_hash.read().await.is_some() {
        return Err(StatusCode::FORBIDDEN);
    }

    if body.password.is_empty() {
        return Err(StatusCode::BAD_REQUEST);
    }

    let salt = SaltString::generate(&mut OsRng);
    let hash = Argon2::default()
        .hash_password(body.password.as_bytes(), &salt)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .to_string();

    // Save to database
    state.app.storage.save_password_hash(&hash).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    // Update in-memory state
    *state.password_hash.write().await = Some(hash);

    // Auto-login: create session
    let token = uuid::Uuid::new_v4().to_string();
    state.sessions.write().await.insert(token.clone());

    let cookie = format!("dbx_session={token}; Path={}; HttpOnly; SameSite=Lax", session_cookie_path(&state));
    Ok((StatusCode::OK, [("set-cookie", cookie.as_str())], Json(serde_json::json!({"ok": true}))).into_response())
}

pub async fn check(State(state): State<Arc<WebState>>, req: Request<axum::body::Body>) -> Json<AuthCheckResponse> {
    if state.password_disabled {
        return Json(AuthCheckResponse { authenticated: true, required: false, setup_required: false });
    }
    let has_password = state.password_hash.read().await.is_some();
    if !has_password {
        return Json(AuthCheckResponse { authenticated: false, required: false, setup_required: true });
    }
    let authenticated = match extract_session_token(&req) {
        Some(token) => has_valid_session(&state, &token).await,
        None => false,
    };
    Json(AuthCheckResponse { authenticated, required: true, setup_required: false })
}

pub async fn change_password(
    State(state): State<Arc<WebState>>,
    Json(body): Json<ChangePasswordRequest>,
) -> Result<Response, StatusCode> {
    let hash_guard = state.password_hash.read().await;
    let hash_str = match hash_guard.as_deref() {
        Some(h) => h.to_string(),
        None => return Err(StatusCode::BAD_REQUEST),
    };
    drop(hash_guard);

    if body.new_password.is_empty() {
        return Err(StatusCode::BAD_REQUEST);
    }

    let parsed_hash = PasswordHash::new(&hash_str).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    if Argon2::default().verify_password(body.old_password.as_bytes(), &parsed_hash).is_err() {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let salt = SaltString::generate(&mut OsRng);
    let new_hash = Argon2::default()
        .hash_password(body.new_password.as_bytes(), &salt)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .to_string();

    state.app.storage.save_password_hash(&new_hash).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    *state.password_hash.write().await = Some(new_hash);

    // A password change is a security boundary: revoke every existing browser
    // and mobile session. Replace the caller's browser session so the page does
    // not remain authenticated locally with a server-side-invalid cookie.
    let token = uuid::Uuid::new_v4().to_string();
    {
        let mut sessions = state.sessions.write().await;
        let mut mobile_sessions = state.mobile_sessions.write().await;
        rotate_sessions_after_password_change(&mut sessions, &mut mobile_sessions, token.clone());
    }

    let cookie = format!("dbx_session={token}; Path={}; HttpOnly; SameSite=Lax", session_cookie_path(&state));
    Ok((StatusCode::OK, [("set-cookie", cookie.as_str())], Json(serde_json::json!({"ok": true}))).into_response())
}

pub async fn logout(State(state): State<Arc<WebState>>, req: Request<axum::body::Body>) -> Response {
    if let Some(token) = extract_session_token(&req) {
        state.sessions.write().await.remove(&token);
        state.mobile_sessions.write().await.remove(&token);
    }
    let cookie = format!("dbx_session=; Path={}; HttpOnly; Max-Age=0", session_cookie_path(&state));
    (StatusCode::OK, [("set-cookie", cookie.as_str())], Json(serde_json::json!({"ok": true}))).into_response()
}

pub fn session_token_from_headers(headers: &axum::http::HeaderMap) -> Option<String> {
    if let Some(authorization) = headers.get("authorization").and_then(|value| value.to_str().ok()) {
        if let Some((scheme, token)) = authorization.trim().split_once(' ') {
            let token = token.trim();
            if scheme.eq_ignore_ascii_case("bearer") && !token.is_empty() && !token.contains(char::is_whitespace) {
                return Some(token.to_string());
            }
        }
    }

    if let Some(cookie_header) = headers.get("cookie").and_then(|value| value.to_str().ok()) {
        for pair in cookie_header.split(';') {
            let pair = pair.trim();
            if let Some(value) = pair.strip_prefix("dbx_session=") {
                if !value.is_empty() {
                    return Some(value.to_string());
                }
            }
        }
    }
    None
}

fn extract_session_token<B>(req: &Request<B>) -> Option<String> {
    session_token_from_headers(req.headers())
}

async fn has_valid_session(state: &WebState, token: &str) -> bool {
    if state.sessions.read().await.contains(token) {
        return true;
    }

    let mut sessions = state.mobile_sessions.write().await;
    match sessions.get(token) {
        Some(session) if session.expires_at > std::time::Instant::now() => true,
        Some(_) => {
            sessions.remove(token);
            false
        }
        None => false,
    }
}

pub async fn auth_middleware(
    State(state): State<Arc<WebState>>,
    req: Request<axum::body::Body>,
    next: Next,
) -> Response {
    // Auth endpoints are always accessible.
    let api_suffix = middleware_api_path_suffix(req.uri().path(), &state.public_base_path);
    if api_suffix.is_some_and(|suffix| suffix.starts_with("auth/")) {
        return next.run(req).await;
    }

    // Non-API requests (static files) are always accessible.
    if api_suffix.is_none() {
        return next.run(req).await;
    }

    if state.password_disabled {
        return next.run(req).await;
    }

    if state.password_hash.read().await.is_none() {
        return StatusCode::UNAUTHORIZED.into_response();
    }

    // Check session token
    if let Some(token) = extract_session_token(&req) {
        if has_valid_session(&state, &token).await {
            return next.run(req).await;
        }
    }

    StatusCode::UNAUTHORIZED.into_response()
}

#[cfg(test)]
mod tests {
    use std::collections::{HashMap, HashSet};
    use std::time::{Duration, Instant};

    use axum::http::{HeaderMap, HeaderValue};

    use crate::state::MobileSession;

    use super::{
        api_path_suffix, middleware_api_path_suffix, prune_expired_mobile_sessions,
        rotate_sessions_after_password_change, session_token_from_headers,
    };

    #[test]
    fn api_path_suffix_handles_root_api_paths() {
        assert_eq!(api_path_suffix("/api/auth/check", "/"), Some("auth/check"));
        assert_eq!(api_path_suffix("/api/query/execute", "/"), Some("query/execute"));
        assert_eq!(api_path_suffix("/dbx/api/auth/check", "/"), None);
    }

    #[test]
    fn api_path_suffix_handles_mounted_api_paths() {
        assert_eq!(api_path_suffix("/dbx/api/auth/check", "/dbx"), Some("auth/check"));
        assert_eq!(api_path_suffix("/tools/dbx/api/query/execute", "/tools/dbx"), Some("query/execute"));
        assert_eq!(api_path_suffix("/dbx/login", "/dbx"), None);
    }

    #[test]
    fn middleware_api_path_suffix_handles_nested_router_paths() {
        assert_eq!(middleware_api_path_suffix("/auth/check", "/"), Some("auth/check"));
        assert_eq!(middleware_api_path_suffix("/connection/list", "/"), Some("connection/list"));
        assert_eq!(middleware_api_path_suffix("/api/connection/list", "/"), Some("connection/list"));
        assert_eq!(middleware_api_path_suffix("/dbx/api/connection/list", "/dbx"), Some("connection/list"));
        assert_eq!(middleware_api_path_suffix("/dbx/login", "/dbx"), None);
    }

    #[test]
    fn bearer_token_takes_precedence_over_browser_cookie() {
        let mut headers = HeaderMap::new();
        headers.insert("authorization", HeaderValue::from_static("Bearer mobile-token"));
        headers.insert("cookie", HeaderValue::from_static("dbx_session=browser-token"));

        assert_eq!(session_token_from_headers(&headers).as_deref(), Some("mobile-token"));
    }

    #[test]
    fn malformed_bearer_token_is_rejected_without_a_cookie() {
        let mut headers = HeaderMap::new();
        headers.insert("authorization", HeaderValue::from_static("Bearer two tokens"));

        assert_eq!(session_token_from_headers(&headers), None);
    }

    #[test]
    fn expired_mobile_sessions_are_pruned_in_bulk() {
        let now = Instant::now();
        let mut sessions = HashMap::from([
            ("expired".to_string(), MobileSession { expires_at: now - Duration::from_secs(1) }),
            ("active".to_string(), MobileSession { expires_at: now + Duration::from_secs(60) }),
        ]);

        prune_expired_mobile_sessions(&mut sessions, now);

        assert!(!sessions.contains_key("expired"));
        assert!(sessions.contains_key("active"));
    }

    #[test]
    fn password_change_revokes_old_browser_and_mobile_sessions() {
        let now = Instant::now();
        let mut browser_sessions = HashSet::from(["caller-session".to_string(), "other-browser".to_string()]);
        let mut mobile_sessions = HashMap::from([
            ("phone".to_string(), MobileSession { expires_at: now + Duration::from_secs(60) }),
            ("tablet".to_string(), MobileSession { expires_at: now + Duration::from_secs(60) }),
        ]);

        rotate_sessions_after_password_change(
            &mut browser_sessions,
            &mut mobile_sessions,
            "replacement-session".to_string(),
        );

        assert_eq!(browser_sessions, HashSet::from(["replacement-session".to_string()]));
        assert!(mobile_sessions.is_empty());
    }
}
