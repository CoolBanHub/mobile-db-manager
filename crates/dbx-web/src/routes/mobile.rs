use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;
use dbx_core::history::{HistoryConnectionFilter, HistoryCursor, HistoryEntry, HistorySearchRequest};
use dbx_core::models::connection::{
    ConnectionConfig, DatabaseType, ProxyTunnelConfig, ProxyType, TransportLayerConfig,
};
use dbx_core::query_cancel::{RunningQueries, RunningTaskMetadata};
use dbx_core::saved_sql::{SavedSqlFile, SavedSqlFolder, SavedSqlLibrary};
use serde::{Deserialize, Serialize};

use crate::error::AppError;
use crate::state::WebState;

const MOBILE_QUERY_DEFAULT_PAGE_SIZE: usize = 50;
const MOBILE_QUERY_MAX_PAGE_SIZE: usize = 200;
const MOBILE_QUERY_MAX_OFFSET: usize = 100_000;
const MOBILE_QUERY_RESULT_BYTE_LIMIT: usize = 2 * 1024 * 1024;
const MOBILE_QUERY_STATEMENT_TIMEOUT_MS: u64 = 30_000;
const MOBILE_TRANSACTION_START_TIMEOUT_MS: u64 = 10_000;
const MOBILE_QUERY_OVERALL_TIMEOUT_SECS: u64 = 35;
const MOBILE_HISTORY_LIMIT: usize = 50;
const MOBILE_SAVED_SQL_NAME_LIMIT: usize = 120;
const MOBILE_TABLE_PAGE_DEFAULT: usize = 30;
const MOBILE_TABLE_PAGE_MAX: usize = 50;
const MOBILE_TABLE_OFFSET_MAX: usize = 1_000_000;
const MOBILE_TABLE_FILTER_MAX: usize = 8;
const MOBILE_TABLE_FILTER_VALUE_MAX: usize = 512;

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MobileConnectionSummary {
    pub id: String,
    pub name: String,
    pub note: String,
    pub db_type: DatabaseType,
    pub host: String,
    pub port: u16,
    pub database: Option<String>,
    pub color: Option<String>,
    pub ssl: bool,
    pub read_only: bool,
    pub is_production: bool,
    pub connect_timeout_secs: u64,
    pub query_timeout_secs: u64,
    pub has_proxy: bool,
    pub has_ca_certificate: bool,
}

impl From<ConnectionConfig> for MobileConnectionSummary {
    fn from(config: ConnectionConfig) -> Self {
        Self {
            id: config.id,
            name: config.name,
            note: config.note,
            db_type: config.db_type,
            host: config.host,
            port: config.port,
            database: config.database,
            color: config.color,
            ssl: config.ssl,
            read_only: config.read_only,
            is_production: config.is_production,
            connect_timeout_secs: config.connect_timeout_secs,
            query_timeout_secs: config.query_timeout_secs,
            has_proxy: config
                .transport_layers
                .iter()
                .any(|layer| matches!(layer, TransportLayerConfig::Proxy(proxy) if proxy.enabled)),
            has_ca_certificate: !config.ca_cert_path.is_empty(),
        }
    }
}

pub async fn load_connections(
    State(state): State<Arc<WebState>>,
) -> Result<Json<Vec<MobileConnectionSummary>>, AppError> {
    let configs = state.app.storage.load_connections().await.map_err(AppError::from)?;
    Ok(Json(configs.into_iter().map(MobileConnectionSummary::from).collect()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileConnectionDraft {
    pub id: Option<String>,
    pub name: String,
    #[serde(default)]
    pub note: String,
    pub db_type: DatabaseType,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    pub database: Option<String>,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub ssl: bool,
    #[serde(default)]
    pub read_only: bool,
    #[serde(default)]
    pub is_production: bool,
    #[serde(default = "dbx_core::models::connection::default_connect_timeout_secs")]
    pub connect_timeout_secs: u64,
    #[serde(default = "dbx_core::models::connection::default_query_timeout_secs")]
    pub query_timeout_secs: u64,
    #[serde(default = "dbx_core::models::connection::default_idle_timeout_secs")]
    pub idle_timeout_secs: u64,
    #[serde(default = "dbx_core::models::connection::default_keepalive_interval_secs")]
    pub keepalive_interval_secs: u64,
    #[serde(default)]
    pub ca_cert_path: String,
    #[serde(default)]
    pub client_cert_path: String,
    #[serde(default)]
    pub client_key_path: String,
    #[serde(default)]
    pub proxy_enabled: bool,
    #[serde(default)]
    pub proxy_type: ProxyType,
    #[serde(default)]
    pub proxy_host: String,
    #[serde(default)]
    pub proxy_port: u16,
    #[serde(default)]
    pub proxy_username: String,
    #[serde(default)]
    pub proxy_password: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileConnectionEditor {
    #[serde(flatten)]
    pub summary: MobileConnectionSummary,
    pub has_password: bool,
    pub username: String,
    pub idle_timeout_secs: u64,
    pub keepalive_interval_secs: u64,
    pub ca_cert_path: String,
    pub client_cert_path: String,
    pub client_key_path: String,
    pub proxy_enabled: bool,
    pub proxy_type: ProxyType,
    pub proxy_host: String,
    pub proxy_port: u16,
    pub proxy_username: String,
    pub has_proxy_password: bool,
}

fn validate_mobile_draft(draft: &MobileConnectionDraft) -> Result<(), AppError> {
    if draft.name.trim().is_empty() {
        return Err(AppError::bad_request("Connection name is required"));
    }
    if draft.host.trim().is_empty() {
        return Err(AppError::bad_request("Connection host is required"));
    }
    if draft.port == 0 {
        return Err(AppError::bad_request("Connection port must be greater than zero"));
    }
    if !(1..=300).contains(&draft.connect_timeout_secs)
        || !(1..=3600).contains(&draft.query_timeout_secs)
        || !(1..=3600).contains(&draft.idle_timeout_secs)
        || !(1..=3600).contains(&draft.keepalive_interval_secs)
    {
        return Err(AppError::bad_request("Connection timeout setting is outside the allowed range"));
    }
    if draft.proxy_enabled && (draft.proxy_host.trim().is_empty() || draft.proxy_port == 0) {
        return Err(AppError::bad_request("Enabled proxy requires a host and port"));
    }
    Ok(())
}

fn proxy_from_config(config: &ConnectionConfig) -> Option<&ProxyTunnelConfig> {
    config.transport_layers.iter().find_map(|layer| match layer {
        TransportLayerConfig::Proxy(proxy) => Some(proxy),
        _ => None,
    })
}

fn editor_from_config(config: ConnectionConfig) -> MobileConnectionEditor {
    let proxy = proxy_from_config(&config).cloned();
    let editor = MobileConnectionEditor {
        summary: MobileConnectionSummary::from(config.clone()),
        has_password: !config.password.is_empty(),
        username: config.username,
        idle_timeout_secs: config.idle_timeout_secs,
        keepalive_interval_secs: config.keepalive_interval_secs,
        ca_cert_path: config.ca_cert_path,
        client_cert_path: config.client_cert_path,
        client_key_path: config.client_key_path,
        proxy_enabled: proxy.as_ref().is_some_and(|value| value.enabled),
        proxy_type: proxy.as_ref().map(|value| value.proxy_type).unwrap_or_default(),
        proxy_host: proxy.as_ref().map(|value| value.host.clone()).unwrap_or_default(),
        proxy_port: proxy.as_ref().map(|value| value.port).unwrap_or(1080),
        proxy_username: proxy.as_ref().map(|value| value.username.clone()).unwrap_or_default(),
        has_proxy_password: proxy.as_ref().is_some_and(|value| !value.password.is_empty()),
    };
    editor
}

fn apply_mobile_draft(
    draft: MobileConnectionDraft,
    existing: Option<ConnectionConfig>,
) -> Result<ConnectionConfig, AppError> {
    validate_mobile_draft(&draft)?;
    let existing_proxy_password =
        existing.as_ref().and_then(proxy_from_config).map(|proxy| proxy.password.clone()).unwrap_or_default();
    let id =
        draft.id.clone().filter(|value| !value.trim().is_empty()).unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let mut config = if let Some(config) = existing {
        config
    } else {
        serde_json::from_value(serde_json::json!({
            "id": id.clone(),
            "name": draft.name.clone(),
            "dbType": draft.db_type,
            "host": draft.host.clone(),
            "port": draft.port,
            "username": draft.username.clone(),
            "password": draft.password.clone(),
            "database": draft.database.clone(),
        }))
        .map_err(|error| AppError::bad_request(error.to_string()))?
    };

    config.id = id;
    config.name = draft.name.trim().to_string();
    config.note = draft.note.trim().to_string();
    config.db_type = draft.db_type;
    config.host = draft.host.trim().to_string();
    config.port = draft.port;
    config.username = draft.username.trim().to_string();
    if !draft.password.is_empty() || config.password.is_empty() {
        config.password = draft.password;
    }
    config.database = draft.database.map(|value| value.trim().to_string()).filter(|value| !value.is_empty());
    config.color = draft.color;
    config.ssl = draft.ssl;
    config.read_only = draft.read_only;
    config.is_production = draft.is_production;
    config.connect_timeout_secs = draft.connect_timeout_secs;
    config.query_timeout_secs = draft.query_timeout_secs;
    config.idle_timeout_secs = draft.idle_timeout_secs;
    config.keepalive_interval_secs = draft.keepalive_interval_secs;
    config.ca_cert_path = draft.ca_cert_path.trim().to_string();
    config.client_cert_path = draft.client_cert_path.trim().to_string();
    config.client_key_path = draft.client_key_path.trim().to_string();

    config.transport_layers.retain(|layer| !matches!(layer, TransportLayerConfig::Proxy(_)));
    if draft.proxy_enabled {
        config.transport_layers.push(TransportLayerConfig::Proxy(ProxyTunnelConfig {
            id: format!("mobile-proxy-{}", config.id),
            name: "Mobile proxy".to_string(),
            enabled: true,
            proxy_type: draft.proxy_type,
            host: draft.proxy_host.trim().to_string(),
            port: draft.proxy_port,
            username: draft.proxy_username.trim().to_string(),
            password: if draft.proxy_password.is_empty() { existing_proxy_password } else { draft.proxy_password },
            test_target: None,
            profile_id: String::new(),
        }));
    }
    Ok(config.canonicalized())
}

pub async fn load_connection_editor(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<MobileConnectionEditor>, AppError> {
    let config = state
        .app
        .storage
        .load_connections()
        .await
        .map_err(AppError::from)?
        .into_iter()
        .find(|config| config.id == id)
        .ok_or_else(|| AppError::not_found("Connection not found"))?;
    Ok(Json(editor_from_config(config)))
}

pub async fn save_mobile_connection(
    State(state): State<Arc<WebState>>,
    Json(draft): Json<MobileConnectionDraft>,
) -> Result<Json<MobileConnectionSummary>, AppError> {
    let mut configs = state.app.storage.load_connections().await.map_err(AppError::from)?;
    let existing_index = draft.id.as_ref().and_then(|id| configs.iter().position(|config| &config.id == id));
    let existing = existing_index.map(|index| configs[index].clone());
    let config = apply_mobile_draft(draft, existing)?;
    if let Some(index) = existing_index {
        configs[index] = config.clone();
    } else {
        configs.push(config.clone());
    }
    state.app.storage.save_connections(&configs).await.map_err(AppError::from)?;
    state.app.remove_connection_pools_detached(&config.id).await;
    state.app.configs.write().await.insert(config.id.clone(), config.clone());
    Ok(Json(MobileConnectionSummary::from(config)))
}

pub async fn test_mobile_connection(
    State(state): State<Arc<WebState>>,
    Json(draft): Json<MobileConnectionDraft>,
) -> Result<Json<dbx_core::models::connection::ConnectionTestResult>, AppError> {
    let existing = if let Some(id) = draft.id.as_deref() {
        state.app.storage.load_connections().await.map_err(AppError::from)?.into_iter().find(|config| config.id == id)
    } else {
        None
    };
    let config = apply_mobile_draft(draft, existing)?;
    crate::routes::connection::run_temporary_connection_test(&state.app, config, true)
        .await
        .map(Json)
        .map_err(AppError::from)
}

pub async fn delete_mobile_connection(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut configs = state.app.storage.load_connections().await.map_err(AppError::from)?;
    let original_len = configs.len();
    configs.retain(|config| config.id != id);
    if configs.len() == original_len {
        return Err(AppError::not_found("Connection not found"));
    }
    state.app.storage.save_connections(&configs).await.map_err(AppError::from)?;
    state.app.configs.write().await.remove(&id);
    state.app.remove_connection_pools_detached(&id).await;
    Ok(Json(serde_json::json!({ "ok": true })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileQueryRequest {
    pub connection_id: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: String,
    pub execution_id: Option<String>,
    pub offset: Option<usize>,
    pub page_size: Option<usize>,
}

fn ensure_mobile_read_only_sql(sql: &str, database_type: DatabaseType) -> Result<(), String> {
    if sql.trim().is_empty() {
        return Err("SQL cannot be empty".to_string());
    }
    if sql.len() > 100_000 {
        return Err("SQL exceeds the 100 KB mobile limit".to_string());
    }
    let statements = dbx_core::sql::split_sql_statements_for_database(sql, database_type);
    if statements.len() != 1 {
        return Err("Mobile query requires exactly one SQL statement".to_string());
    }
    let risk = dbx_core::sql_risk::classify_sql_risk_for_database(sql, database_type)?;
    if risk != dbx_core::sql_risk::SqlRisk::ReadOnly {
        return Err(format!("Mobile query is read-only; {risk} statement blocked"));
    }
    Ok(())
}

fn ensure_mobile_result_size<T: Serialize>(result: &T) -> Result<(), String> {
    let result_bytes = serde_json::to_vec(result).map_err(|error| error.to_string())?;
    if result_bytes.len() > MOBILE_QUERY_RESULT_BYTE_LIMIT {
        return Err("Query result exceeds the 2 MiB mobile response limit".to_string());
    }
    Ok(())
}

fn mobile_transaction_error(error: dbx_core::query::ManualTransactionError) -> AppError {
    use dbx_core::query::ManualTransactionErrorKind;

    let message = error.to_string();
    match error.kind {
        ManualTransactionErrorKind::Query => AppError::bad_request(message),
        ManualTransactionErrorKind::Timeout => AppError::request_timeout(message),
        ManualTransactionErrorKind::Connection => AppError::bad_gateway(message),
        ManualTransactionErrorKind::Internal => AppError::internal(message),
    }
}

fn mobile_query_error(error: String) -> AppError {
    let normalized = error.to_ascii_lowercase();
    if normalized.contains("canceled") || normalized.contains("cancelled") {
        AppError::request_timeout("Mobile query was cancelled")
    } else if normalized.contains("timed out") || normalized.contains("timeout") {
        AppError::request_timeout(error)
    } else if normalized.contains("connection")
        || normalized.contains("broken pipe")
        || normalized.contains("network")
        || normalized.contains("closed")
    {
        AppError::bad_gateway(error)
    } else {
        AppError::bad_request(error)
    }
}

fn validate_mobile_execution_id(execution_id: &str) -> Result<(), String> {
    if execution_id.is_empty()
        || execution_id.len() > 128
        || !execution_id.chars().all(|character| character.is_ascii_alphanumeric() || matches!(character, '-' | '_'))
    {
        return Err("Invalid mobile query execution id".to_string());
    }
    Ok(())
}

fn validate_mobile_query_page(request: &MobileQueryRequest) -> Result<(usize, usize), String> {
    let offset = request.offset.unwrap_or(0);
    let page_size = request.page_size.unwrap_or(MOBILE_QUERY_DEFAULT_PAGE_SIZE);
    if !(1..=MOBILE_QUERY_MAX_PAGE_SIZE).contains(&page_size) {
        return Err(format!("Mobile query page size must contain 1-{MOBILE_QUERY_MAX_PAGE_SIZE} rows"));
    }
    if offset > MOBILE_QUERY_MAX_OFFSET {
        return Err(format!("Mobile query offset cannot exceed {MOBILE_QUERY_MAX_OFFSET} rows"));
    }
    offset
        .checked_add(page_size)
        .and_then(|value| value.checked_add(1))
        .ok_or_else(|| "Mobile query page is too large".to_string())?;
    Ok((offset, page_size))
}

struct MobileQueryCancellationGuard {
    running_queries: RunningQueries,
    execution_id: String,
    armed: bool,
}

impl MobileQueryCancellationGuard {
    fn new(running_queries: RunningQueries, execution_id: String) -> Self {
        Self { running_queries, execution_id, armed: true }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for MobileQueryCancellationGuard {
    fn drop(&mut self) {
        if self.armed {
            self.running_queries.cancel(&self.execution_id);
        }
    }
}

async fn run_read_only_query(
    state: &WebState,
    request: &MobileQueryRequest,
    config: &ConnectionConfig,
) -> Result<dbx_core::db::QueryResult, AppError> {
    let (offset, page_size) = validate_mobile_query_page(request).map_err(AppError::bad_request)?;
    let fetch_rows = offset + page_size + 1;
    let execution_id = request.execution_id.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(MOBILE_QUERY_OVERALL_TIMEOUT_SECS);
    let registered = state.app.running_queries.register_task(
        execution_id.clone(),
        RunningTaskMetadata::query(
            request.connection_id.clone(),
            request.database.clone(),
            Some(format!("mobile:{execution_id}")),
        ),
    );
    let cancel_token = registered.token();
    // Axum drops the handler future when a phone disconnects. Keep a guard
    // beside that future so a dropped HTTP connection also triggers the
    // driver's cancellation/interrupt path before the registration is removed.
    let mut disconnect_guard =
        MobileQueryCancellationGuard::new(state.app.running_queries.clone(), execution_id.clone());

    // Keep a final dialect-aware guard directly beside execution. The earlier
    // risk classifier provides friendly validation; this is the server-side
    // safety barrier shared by every supported SQL driver.
    dbx_core::query_execution_sql::check_read_only(&request.sql, &config.name, config.db_type)
        .map_err(AppError::bad_request)?;

    let execution = tokio::time::timeout_at(
        deadline,
        dbx_core::query::execute_sql_statement_with_options(
            &state.app,
            &request.connection_id,
            &request.database,
            &request.sql,
            request.schema.as_deref(),
            Some(cancel_token),
            dbx_core::query::QueryExecutionOptions {
                max_rows: Some(fetch_rows),
                timeout_secs: Some(MOBILE_QUERY_STATEMENT_TIMEOUT_MS / 1_000),
                execution_id: Some(execution_id.clone()),
                ..Default::default()
            },
        ),
    )
    .await;
    let result = match execution {
        Ok(Ok(result)) => {
            disconnect_guard.disarm();
            result
        }
        Ok(Err(error)) => {
            disconnect_guard.disarm();
            return Err(mobile_query_error(error));
        }
        Err(_) => {
            state.app.running_queries.cancel(&execution_id);
            disconnect_guard.disarm();
            return Err(AppError::request_timeout("Mobile query exceeded the 35-second server limit"));
        }
    };
    drop(registered);
    let mut result = result;
    let has_more = result.rows.len() > offset + page_size || result.truncated;
    result.rows = result.rows.into_iter().skip(offset).take(page_size).collect();
    result.has_more = has_more;
    result.truncated = has_more;
    ensure_mobile_result_size(&result).map_err(AppError::payload_too_large)?;
    Ok(result)
}

pub async fn execute_read_only_query(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileQueryRequest>,
) -> Result<Json<dbx_core::db::QueryResult>, AppError> {
    let config = state
        .app
        .storage
        .load_connections()
        .await
        .map_err(AppError::from)?
        .into_iter()
        .find(|config| config.id == request.connection_id)
        .ok_or_else(|| AppError::not_found("Connection not found"))?;
    if request.sql.len() > 100_000 {
        return Err(AppError::payload_too_large("SQL exceeds the 100 KB mobile limit"));
    }
    if let Some(execution_id) = request.execution_id.as_deref() {
        validate_mobile_execution_id(execution_id).map_err(AppError::bad_request)?;
    }
    let (offset, page_size) = validate_mobile_query_page(&request).map_err(AppError::bad_request)?;
    ensure_mobile_read_only_sql(&request.sql, config.db_type).map_err(AppError::bad_request)?;

    let started_at = chrono::Utc::now();
    let started = std::time::Instant::now();
    let result = run_read_only_query(&state, &request, &config).await;
    if offset == 0 {
        let history = HistoryEntry {
            id: uuid::Uuid::new_v4().to_string(),
            connection_id: request.connection_id.clone(),
            connection_name: config.name,
            database: request.database.clone(),
            sql: request.sql.clone(),
            executed_at: started_at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
            execution_time_ms: started.elapsed().as_millis(),
            success: result.is_ok(),
            error: result.as_ref().err().map(|error| error.message.clone()),
            activity_kind: "query".to_string(),
            operation: "mobile_read_only_query".to_string(),
            target: request.schema.clone().unwrap_or_default(),
            affected_rows: result.as_ref().ok().and_then(|value| i64::try_from(value.affected_rows).ok()),
            rollback_sql: None,
            details_json: Some(serde_json::json!({ "offset": offset, "pageSize": page_size }).to_string()),
        };
        if let Err(error) = state.app.storage.save_history_entry(&history).await {
            log::warn!("Failed to save mobile query history: {error}");
        }
    }
    result.map(Json)
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileQueryCancelResponse {
    pub cancelled: bool,
}

pub async fn cancel_read_only_query(
    State(state): State<Arc<WebState>>,
    Path(execution_id): Path<String>,
) -> Result<Json<MobileQueryCancelResponse>, AppError> {
    validate_mobile_execution_id(&execution_id).map_err(AppError::bad_request)?;
    Ok(Json(MobileQueryCancelResponse { cancelled: state.app.running_queries.cancel(&execution_id) }))
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileTableRequest {
    pub connection_id: String,
    pub database: String,
    pub schema: Option<String>,
    pub table: String,
    pub offset: Option<usize>,
    pub limit: Option<usize>,
    #[serde(default)]
    pub filters: Vec<MobileTableFilter>,
    pub sort: Option<MobileTableSort>,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MobileTableFilterOperator {
    Equals,
    NotEquals,
    Contains,
    StartsWith,
    EndsWith,
    GreaterThan,
    GreaterThanOrEqual,
    LessThan,
    LessThanOrEqual,
    IsNull,
    IsNotNull,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileTableFilter {
    pub column: String,
    pub operator: MobileTableFilterOperator,
    #[serde(default)]
    pub value: String,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MobileTableSortDirection {
    Asc,
    Desc,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileTableSort {
    pub column: String,
    pub direction: MobileTableSortDirection,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileTableTemplateResponse {
    pub sql: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileTableDataResponse {
    pub result: dbx_core::db::QueryResult,
    pub offset: usize,
    pub limit: usize,
    pub has_more: bool,
    pub select_template: String,
}

fn validate_mobile_table_request(request: &MobileTableRequest) -> Result<(usize, usize), String> {
    for (label, value) in [
        ("Connection", request.connection_id.as_str()),
        ("Database", request.database.as_str()),
        ("Table", request.table.as_str()),
    ] {
        if value.trim().is_empty() || value.chars().count() > 256 {
            return Err(format!("{label} must contain 1-256 characters"));
        }
    }
    if request.schema.as_ref().is_some_and(|schema| schema.chars().count() > 256) {
        return Err("Schema exceeds 256 characters".to_string());
    }
    if request.filters.len() > MOBILE_TABLE_FILTER_MAX {
        return Err(format!("Table filtering supports at most {MOBILE_TABLE_FILTER_MAX} conditions"));
    }
    for filter in &request.filters {
        if filter.column.trim().is_empty() || filter.column.chars().count() > 256 {
            return Err("Filter column must contain 1-256 characters".to_string());
        }
        if filter.value.chars().count() > MOBILE_TABLE_FILTER_VALUE_MAX {
            return Err(format!("Filter value cannot exceed {MOBILE_TABLE_FILTER_VALUE_MAX} characters"));
        }
    }
    if request.sort.as_ref().is_some_and(|sort| sort.column.trim().is_empty() || sort.column.chars().count() > 256) {
        return Err("Sort column must contain 1-256 characters".to_string());
    }
    let limit = request.limit.unwrap_or(MOBILE_TABLE_PAGE_DEFAULT);
    if !(1..=MOBILE_TABLE_PAGE_MAX).contains(&limit) {
        return Err(format!("Table page size must contain 1-{MOBILE_TABLE_PAGE_MAX} rows"));
    }
    let offset = request.offset.unwrap_or(0);
    if offset > MOBILE_TABLE_OFFSET_MAX {
        return Err(format!("Table offset cannot exceed {MOBILE_TABLE_OFFSET_MAX} rows"));
    }
    Ok((offset, limit))
}

const MOBILE_TABLE_DATABASE_TYPES: [DatabaseType; 6] = [
    DatabaseType::Postgres,
    DatabaseType::Mysql,
    DatabaseType::SqlServer,
    DatabaseType::Oracle,
    DatabaseType::Sqlite,
    DatabaseType::ClickHouse,
];

fn supports_mobile_table_browsing(database_type: DatabaseType) -> bool {
    MOBILE_TABLE_DATABASE_TYPES.contains(&database_type)
}

fn mobile_sql_literal(database_type: DatabaseType, value: &str) -> String {
    let value = value.replace('\'', "''");
    let value = if matches!(database_type, DatabaseType::Mysql | DatabaseType::ClickHouse) {
        value.replace('\\', "\\\\")
    } else {
        value
    };
    format!("'{value}'")
}

fn mobile_like_pattern(database_type: DatabaseType, value: &str, prefix: &str, suffix: &str) -> String {
    let escaped = value.replace('!', "!!").replace('%', "!%").replace('_', "!_");
    mobile_sql_literal(database_type, &format!("{prefix}{escaped}{suffix}"))
}

fn mobile_text_expression(database_type: DatabaseType, column: &str) -> String {
    match database_type {
        DatabaseType::Mysql => format!("CAST({column} AS CHAR)"),
        DatabaseType::SqlServer => format!("CAST({column} AS NVARCHAR(MAX))"),
        DatabaseType::Oracle => format!("TO_CHAR({column})"),
        DatabaseType::ClickHouse => format!("toString({column})"),
        _ => format!("CAST({column} AS TEXT)"),
    }
}

fn build_mobile_table_sql(
    database_type: DatabaseType,
    schema: Option<&str>,
    table: &str,
    columns: &[dbx_core::db::ColumnInfo],
    offset: usize,
    limit: usize,
    filters: &[MobileTableFilter],
    sort: Option<&MobileTableSort>,
) -> Result<(String, String), String> {
    let dialect = Some(database_type);
    let quoted_columns = columns
        .iter()
        .map(|column| dbx_core::sql_dialect::quote_table_identifier(dialect, &column.name))
        .collect::<Vec<_>>()
        .join(", ");
    let qualified_table =
        if database_type == DatabaseType::ClickHouse && schema.is_some_and(|schema| !schema.trim().is_empty()) {
            format!(
                "{}.{}",
                dbx_core::sql_dialect::quote_table_identifier(dialect, schema.unwrap()),
                dbx_core::sql_dialect::quote_table_identifier(dialect, table)
            )
        } else {
            dbx_core::sql_dialect::qualified_table_name(dialect, schema, table)
        };
    let column_exists = |name: &str| columns.iter().any(|column| column.name == name);
    let predicates = filters
        .iter()
        .map(|filter| {
            if !column_exists(&filter.column) {
                return Err(format!("Filter column '{}' does not exist", filter.column));
            }
            let column = dbx_core::sql_dialect::quote_table_identifier(dialect, &filter.column);
            let literal = || mobile_sql_literal(database_type, &filter.value);
            let text_column = || mobile_text_expression(database_type, &column);
            let predicate = match filter.operator {
                MobileTableFilterOperator::Equals => {
                    if filter.value.is_empty() {
                        format!("{column} = ''")
                    } else {
                        format!("{column} = {}", literal())
                    }
                }
                MobileTableFilterOperator::NotEquals => {
                    format!("{column} <> {}", literal())
                }
                MobileTableFilterOperator::Contains => {
                    if database_type == DatabaseType::ClickHouse {
                        format!("position({}, {}) > 0", text_column(), literal())
                    } else {
                        format!(
                            "{} LIKE {} ESCAPE '!'",
                            text_column(),
                            mobile_like_pattern(database_type, &filter.value, "%", "%")
                        )
                    }
                }
                MobileTableFilterOperator::StartsWith => {
                    if database_type == DatabaseType::ClickHouse {
                        format!("startsWith({}, {})", text_column(), literal())
                    } else {
                        format!(
                            "{} LIKE {} ESCAPE '!'",
                            text_column(),
                            mobile_like_pattern(database_type, &filter.value, "", "%")
                        )
                    }
                }
                MobileTableFilterOperator::EndsWith => {
                    if database_type == DatabaseType::ClickHouse {
                        format!("endsWith({}, {})", text_column(), literal())
                    } else {
                        format!(
                            "{} LIKE {} ESCAPE '!'",
                            text_column(),
                            mobile_like_pattern(database_type, &filter.value, "%", "")
                        )
                    }
                }
                MobileTableFilterOperator::GreaterThan => {
                    format!("{column} > {}", literal())
                }
                MobileTableFilterOperator::GreaterThanOrEqual => {
                    format!("{column} >= {}", literal())
                }
                MobileTableFilterOperator::LessThan => {
                    format!("{column} < {}", literal())
                }
                MobileTableFilterOperator::LessThanOrEqual => {
                    format!("{column} <= {}", literal())
                }
                MobileTableFilterOperator::IsNull => format!("{column} IS NULL"),
                MobileTableFilterOperator::IsNotNull => format!("{column} IS NOT NULL"),
            };
            Ok(predicate)
        })
        .collect::<Result<Vec<_>, String>>()?;
    let where_clause =
        if predicates.is_empty() { String::new() } else { format!(" WHERE {}", predicates.join(" AND ")) };
    let primary_key_names =
        columns.iter().filter(|column| column.is_primary_key).map(|column| column.name.as_str()).collect::<Vec<_>>();
    let mut order_columns = Vec::new();
    if let Some(sort) = sort {
        if !column_exists(&sort.column) {
            return Err(format!("Sort column '{}' does not exist", sort.column));
        }
        let direction = match sort.direction {
            MobileTableSortDirection::Asc => "ASC",
            MobileTableSortDirection::Desc => "DESC",
        };
        order_columns
            .push(format!("{} {direction}", dbx_core::sql_dialect::quote_table_identifier(dialect, &sort.column)));
    }
    order_columns.extend(
        primary_key_names
            .iter()
            .filter(|column| sort.is_none_or(|sort| sort.column != **column))
            .map(|column| format!("{} ASC", dbx_core::sql_dialect::quote_table_identifier(dialect, column))),
    );
    let order_by =
        if order_columns.is_empty() { String::new() } else { format!(" ORDER BY {}", order_columns.join(", ")) };
    let build_select = |row_limit: usize, row_offset: usize| {
        match database_type {
        DatabaseType::SqlServer if row_offset == 0 => {
            format!("SELECT TOP ({row_limit}) {quoted_columns} FROM {qualified_table}{where_clause}{order_by};")
        }
        DatabaseType::SqlServer => {
            let paging_order =
                if order_columns.is_empty() { "(SELECT NULL)".to_string() } else { order_columns.join(", ") };
            format!(
                "SELECT {quoted_columns} FROM {qualified_table}{where_clause} ORDER BY {paging_order} OFFSET {row_offset} ROWS FETCH NEXT {row_limit} ROWS ONLY;"
            )
        }
        DatabaseType::Oracle => {
            let inner = format!("SELECT {quoted_columns} FROM {qualified_table}{where_clause}{order_by}");
            if row_offset == 0 {
                format!("SELECT {quoted_columns} FROM ({inner}) WHERE ROWNUM <= {row_limit}")
            } else {
                let end = row_offset + row_limit;
                format!(
                    "SELECT {quoted_columns} FROM (SELECT dbx_inner.*, ROWNUM AS \"__dbx_row_num\" FROM ({inner}) dbx_inner WHERE ROWNUM <= {end}) WHERE \"__dbx_row_num\" > {row_offset}"
                )
            }
        }
        _ => format!(
            "SELECT {quoted_columns} FROM {qualified_table}{where_clause}{order_by} LIMIT {row_limit} OFFSET {row_offset};"
        ),
    }
    };
    let page_sql = build_select(limit + 1, offset);
    let select_template = build_select(MOBILE_QUERY_MAX_PAGE_SIZE, 0);
    Ok((page_sql, select_template))
}

async fn load_mobile_table_columns(
    state: &WebState,
    request: &MobileTableRequest,
) -> Result<(ConnectionConfig, Vec<dbx_core::db::ColumnInfo>), AppError> {
    let config = state
        .app
        .storage
        .load_connections()
        .await
        .map_err(AppError::from)?
        .into_iter()
        .find(|config| config.id == request.connection_id)
        .ok_or_else(|| AppError::not_found("Connection not found"))?;
    if !supports_mobile_table_browsing(config.db_type) {
        return Err(AppError::bad_request(
            "Mobile table browsing supports PostgreSQL, MySQL, SQL Server, Oracle, SQLite, and ClickHouse",
        ));
    }
    let columns = dbx_core::schema::get_columns_core(
        &state.app,
        &request.connection_id,
        &request.database,
        request.schema.as_deref().unwrap_or(""),
        &request.table,
    )
    .await
    .map_err(AppError::bad_gateway)?;
    if columns.is_empty() {
        return Err(AppError::not_found("Table has no visible columns or no longer exists"));
    }
    Ok((config, columns))
}

async fn run_mobile_table_query(
    state: &WebState,
    request: &MobileTableRequest,
    config: &ConnectionConfig,
    sql: &str,
    row_limit: usize,
) -> Result<dbx_core::db::QueryResult, AppError> {
    if config.db_type != DatabaseType::Postgres {
        return run_read_only_query(
            state,
            &MobileQueryRequest {
                connection_id: request.connection_id.clone(),
                database: request.database.clone(),
                schema: request.schema.clone(),
                sql: sql.to_string(),
                execution_id: None,
                offset: Some(0),
                page_size: Some(row_limit),
            },
            config,
        )
        .await;
    }

    let app = Arc::clone(&state.app);
    let request = request.clone();
    let sql = sql.to_string();
    // Keep the whole transaction lifecycle in a detached task. If the phone
    // drops the HTTP request, Tokio still carries this task through rollback.
    tokio::spawn(async move {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(MOBILE_QUERY_OVERALL_TIMEOUT_SECS);
        let transaction_id = dbx_core::query::begin_postgres_read_only_transaction(
            &app,
            &request.connection_id,
            &request.database,
            request.schema.as_deref().filter(|schema| !schema.trim().is_empty()),
            MOBILE_QUERY_STATEMENT_TIMEOUT_MS,
            MOBILE_TRANSACTION_START_TIMEOUT_MS,
        )
        .await
        .map_err(mobile_transaction_error)?;
        let execution = tokio::time::timeout_at(
            deadline,
            dbx_core::query::execute_in_manual_transaction_classified(
                &app,
                &transaction_id,
                &sql,
                &request.database,
                request.schema.as_deref(),
                Some(row_limit),
            ),
        )
        .await;
        let cleanup_app = Arc::clone(&app);
        let cleanup_transaction_id = transaction_id.clone();
        let cleanup = tokio::spawn(async move {
            dbx_core::query::rollback_manual_transaction(&cleanup_app, &cleanup_transaction_id).await
        });
        let rollback = match tokio::time::timeout_at(deadline, cleanup).await {
            Ok(Ok(result)) => result,
            Ok(Err(error)) => return Err(AppError::internal(format!("Read-only table cleanup task failed: {error}"))),
            Err(_) => {
                return Err(AppError::request_timeout(
                    "Table preview exceeded the 35-second limit; transaction cleanup continues in the background",
                ))
            }
        };
        let mut results = match execution {
            Ok(Ok(results)) => results,
            Ok(Err(error)) => return Err(mobile_transaction_error(error)),
            Err(_) => return Err(AppError::request_timeout("Table preview exceeded the server time limit")),
        };
        rollback.map_err(AppError::internal)?;
        results.pop().ok_or_else(|| AppError::internal("Table preview returned no result"))
    })
    .await
    .map_err(|error| AppError::internal(format!("Read-only table task failed: {error}")))?
}

pub async fn build_mobile_table_template(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileTableRequest>,
) -> Result<Json<MobileTableTemplateResponse>, AppError> {
    let (offset, limit) = validate_mobile_table_request(&request).map_err(AppError::bad_request)?;
    let (config, columns) = load_mobile_table_columns(&state, &request).await?;
    let (_, sql) = build_mobile_table_sql(
        config.db_type,
        request.schema.as_deref(),
        &request.table,
        &columns,
        offset,
        limit,
        &request.filters,
        request.sort.as_ref(),
    )
    .map_err(AppError::bad_request)?;
    ensure_mobile_read_only_sql(&sql, config.db_type).map_err(AppError::internal)?;
    Ok(Json(MobileTableTemplateResponse { sql }))
}

pub async fn load_mobile_table_data(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileTableRequest>,
) -> Result<Json<MobileTableDataResponse>, AppError> {
    let (offset, limit) = validate_mobile_table_request(&request).map_err(AppError::bad_request)?;
    let (config, columns) = load_mobile_table_columns(&state, &request).await?;
    let (sql, select_template) = build_mobile_table_sql(
        config.db_type,
        request.schema.as_deref(),
        &request.table,
        &columns,
        offset,
        limit,
        &request.filters,
        request.sort.as_ref(),
    )
    .map_err(AppError::bad_request)?;
    ensure_mobile_read_only_sql(&sql, config.db_type).map_err(AppError::internal)?;
    let mut result = run_mobile_table_query(&state, &request, &config, &sql, limit + 1).await?;
    let has_more = result.rows.len() > limit;
    result.rows.truncate(limit);
    result.has_more = has_more;
    result.truncated = false;
    let response = MobileTableDataResponse { result, offset, limit, has_more, select_template };
    ensure_mobile_result_size(&response).map_err(AppError::payload_too_large)?;
    Ok(Json(response))
}

#[derive(Deserialize)]
pub struct MobileHistoryQuery {
    pub limit: Option<usize>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileHistoryEntry {
    pub id: String,
    pub connection_id: String,
    pub connection_name: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: String,
    pub executed_at: String,
    pub execution_time_ms: u128,
    pub success: bool,
    pub error: Option<String>,
}

impl From<HistoryEntry> for MobileHistoryEntry {
    fn from(entry: HistoryEntry) -> Self {
        let schema =
            (entry.operation == "mobile_read_only_query").then_some(entry.target).filter(|value| !value.is_empty());
        Self {
            id: entry.id,
            connection_id: entry.connection_id,
            connection_name: entry.connection_name,
            database: entry.database,
            schema,
            sql: entry.sql,
            executed_at: entry.executed_at,
            execution_time_ms: entry.execution_time_ms,
            success: entry.success,
            error: entry.error,
        }
    }
}

pub async fn load_query_history(
    State(state): State<Arc<WebState>>,
    Query(query): Query<MobileHistoryQuery>,
) -> Result<Json<Vec<MobileHistoryEntry>>, AppError> {
    let limit = query.limit.unwrap_or(MOBILE_HISTORY_LIMIT).clamp(1, MOBILE_HISTORY_LIMIT);
    let entries =
        state.app.storage.load_history_entries(limit, 0, Some("query".to_string())).await.map_err(AppError::from)?;
    Ok(Json(entries.into_iter().map(MobileHistoryEntry::from).collect()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileHistorySearchRequest {
    #[serde(default)]
    pub search_text: String,
    pub connection_id: Option<String>,
    pub success: Option<bool>,
    pub started_at: Option<String>,
    pub ended_at: Option<String>,
    pub cursor: Option<MobileHistoryCursor>,
    pub limit: Option<usize>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileHistoryCursor {
    pub executed_at: String,
    pub id: String,
}

impl From<MobileHistoryCursor> for HistoryCursor {
    fn from(cursor: MobileHistoryCursor) -> Self {
        Self { executed_at: cursor.executed_at, id: cursor.id }
    }
}

impl From<HistoryCursor> for MobileHistoryCursor {
    fn from(cursor: HistoryCursor) -> Self {
        Self { executed_at: cursor.executed_at, id: cursor.id }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileHistoryPage {
    pub entries: Vec<MobileHistoryEntry>,
    pub next_cursor: Option<MobileHistoryCursor>,
    pub total: usize,
}

fn validate_history_date(value: Option<String>, field: &str) -> Result<Option<String>, AppError> {
    value
        .map(|value| {
            chrono::DateTime::parse_from_rfc3339(&value)
                .map(|_| value)
                .map_err(|_| AppError::bad_request(format!("{field} must be an RFC 3339 timestamp")))
        })
        .transpose()
}

fn validate_history_id(id: &str) -> Result<(), AppError> {
    if id.is_empty() || id.len() > 128 {
        return Err(AppError::bad_request("Invalid history id"));
    }
    Ok(())
}

pub async fn search_query_history(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileHistorySearchRequest>,
) -> Result<Json<MobileHistoryPage>, AppError> {
    if request.search_text.len() > 500 {
        return Err(AppError::bad_request("History search text exceeds 500 characters"));
    }
    if request.connection_id.as_ref().is_some_and(|value| value.len() > 128) {
        return Err(AppError::bad_request("Invalid connection id"));
    }
    let started_at = validate_history_date(request.started_at, "startedAt")?;
    let ended_at = validate_history_date(request.ended_at, "endedAt")?;
    if let Some((start, end)) = started_at.as_ref().zip(ended_at.as_ref()) {
        let start =
            chrono::DateTime::parse_from_rfc3339(start).map_err(|_| AppError::bad_request("Invalid startedAt"))?;
        let end = chrono::DateTime::parse_from_rfc3339(end).map_err(|_| AppError::bad_request("Invalid endedAt"))?;
        if start > end {
            return Err(AppError::bad_request("startedAt must not be later than endedAt"));
        }
    }
    if let Some(cursor) = &request.cursor {
        validate_history_id(&cursor.id)?;
        validate_history_date(Some(cursor.executed_at.clone()), "cursor.executedAt")?;
    }
    let connections = request
        .connection_id
        .filter(|value| !value.is_empty())
        .map(|connection_id| vec![HistoryConnectionFilter { connection_id, connection_name: String::new() }])
        .unwrap_or_default();
    let result = state
        .app
        .storage
        .search_history_entries(HistorySearchRequest {
            search_text: request.search_text,
            connections,
            databases: Vec::new(),
            activity_kind: Some("query".to_string()),
            success: request.success,
            started_at,
            ended_at,
            cursor: request.cursor.map(HistoryCursor::from),
            limit: request.limit.unwrap_or(20).clamp(1, MOBILE_HISTORY_LIMIT),
        })
        .await
        .map_err(AppError::from)?;
    Ok(Json(MobileHistoryPage {
        entries: result.entries.into_iter().map(MobileHistoryEntry::from).collect(),
        next_cursor: result.next_cursor.map(MobileHistoryCursor::from),
        total: result.total,
    }))
}

pub async fn load_query_history_entry(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<MobileHistoryEntry>, AppError> {
    validate_history_id(&id)?;
    let entry = state
        .app
        .storage
        .load_history_entry(&id)
        .await
        .map_err(AppError::from)?
        .filter(|entry| entry.activity_kind == "query")
        .ok_or_else(|| AppError::not_found("History entry not found"))?;
    Ok(Json(MobileHistoryEntry::from(entry)))
}

pub async fn clear_query_history(State(state): State<Arc<WebState>>) -> Result<Json<()>, AppError> {
    state.app.storage.clear_history_by_activity_kind("query").await.map_err(AppError::from)?;
    Ok(Json(()))
}

pub async fn delete_query_history_entry(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<()>, AppError> {
    validate_history_id(&id)?;
    let is_query = state
        .app
        .storage
        .load_history_entry(&id)
        .await
        .map_err(AppError::from)?
        .is_some_and(|entry| entry.activity_kind == "query");
    if !is_query {
        return Err(AppError::not_found("History entry not found"));
    }
    state.app.storage.delete_history_entry(&id).await.map_err(AppError::from)?;
    Ok(Json(()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileSaveSqlRequest {
    pub id: Option<String>,
    pub connection_id: String,
    #[serde(default)]
    pub folder_id: Option<String>,
    pub name: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: Option<String>,
}

fn validate_saved_sql_request(request: &MobileSaveSqlRequest) -> Result<(), String> {
    if request.connection_id.trim().is_empty() || request.connection_id.chars().count() > 256 {
        return Err("Saved SQL connection must contain 1-256 characters".to_string());
    }
    if request.name.trim().is_empty() || request.name.chars().count() > MOBILE_SAVED_SQL_NAME_LIMIT {
        return Err(format!("Saved SQL name must contain 1-{MOBILE_SAVED_SQL_NAME_LIMIT} characters"));
    }
    if request.database.trim().is_empty() || request.database.chars().count() > 256 {
        return Err("Saved SQL database must contain 1-256 characters".to_string());
    }
    if request.schema.as_ref().is_some_and(|schema| schema.chars().count() > 256) {
        return Err("Saved SQL schema exceeds 256 characters".to_string());
    }
    if request.sql.as_ref().is_some_and(|sql| sql.trim().is_empty() || sql.len() > 100_000) {
        return Err("Saved SQL must contain 1-100 KB of SQL".to_string());
    }
    if request.id.as_ref().is_some_and(|id| id.is_empty() || id.len() > 128) {
        return Err("Invalid saved SQL id".to_string());
    }
    if request.folder_id.as_ref().is_some_and(|id| id.is_empty() || id.len() > 128) {
        return Err("Invalid saved SQL folder id".to_string());
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileSaveSqlFolderRequest {
    pub id: Option<String>,
    pub connection_id: String,
    pub parent_folder_id: Option<String>,
    pub name: String,
}

fn validate_saved_sql_folder_request(request: &MobileSaveSqlFolderRequest) -> Result<(), String> {
    if request.connection_id.trim().is_empty() || request.connection_id.chars().count() > 256 {
        return Err("Saved SQL folder connection must contain 1-256 characters".to_string());
    }
    if request.name.trim().is_empty() || request.name.chars().count() > MOBILE_SAVED_SQL_NAME_LIMIT {
        return Err(format!("Folder name must contain 1-{MOBILE_SAVED_SQL_NAME_LIMIT} characters"));
    }
    for id in [request.id.as_ref(), request.parent_folder_id.as_ref()].into_iter().flatten() {
        if id.is_empty() || id.len() > 128 {
            return Err("Invalid saved SQL folder id".to_string());
        }
    }
    if request.id.is_some() && request.id == request.parent_folder_id {
        return Err("A folder cannot be moved into itself".to_string());
    }
    Ok(())
}

fn validate_saved_sql_folder_target(
    library: &SavedSqlLibrary,
    connection_id: &str,
    folder_id: Option<&str>,
    moving_folder_id: Option<&str>,
) -> Result<(), String> {
    let Some(folder_id) = folder_id else {
        return Ok(());
    };
    let folder = library
        .folders
        .iter()
        .find(|folder| folder.id == folder_id)
        .ok_or_else(|| "Saved SQL folder not found".to_string())?;
    if folder.connection_id != connection_id {
        return Err("Saved SQL folder belongs to another connection".to_string());
    }
    if let Some(moving_folder_id) = moving_folder_id {
        let mut current = Some(folder);
        while let Some(folder) = current {
            if folder.id == moving_folder_id {
                return Err("A folder cannot be moved into itself or one of its descendants".to_string());
            }
            current = folder
                .parent_folder_id
                .as_deref()
                .and_then(|parent_id| library.folders.iter().find(|candidate| candidate.id == parent_id));
        }
    }
    Ok(())
}

pub async fn load_saved_sql_library(State(state): State<Arc<WebState>>) -> Result<Json<SavedSqlLibrary>, AppError> {
    state.app.storage.load_saved_sql_library_summary().await.map(Json).map_err(AppError::from)
}

pub async fn load_saved_sql_file(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<Option<SavedSqlFile>>, AppError> {
    let file = state.app.storage.load_saved_sql_file(&id).await.map_err(AppError::from)?;
    if let Some(file) = &file {
        if file.sql.len() > 100_000 {
            return Err(AppError::payload_too_large("Saved SQL exceeds the 100 KB mobile limit"));
        }
    }
    Ok(Json(file))
}

pub async fn save_saved_sql_file(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileSaveSqlRequest>,
) -> Result<Json<SavedSqlFile>, AppError> {
    if request.sql.as_ref().is_some_and(|sql| sql.len() > 100_000) {
        return Err(AppError::payload_too_large("Saved SQL exceeds the 100 KB mobile limit"));
    }
    validate_saved_sql_request(&request).map_err(AppError::bad_request)?;
    let connection_exists = state
        .app
        .storage
        .load_connections()
        .await
        .map_err(AppError::from)?
        .iter()
        .any(|config| config.id == request.connection_id);
    if !connection_exists {
        return Err(AppError::not_found("Connection not found"));
    }

    let existing = match request.id.as_deref() {
        Some(id) => state.app.storage.load_saved_sql_file(id).await.map_err(AppError::from)?,
        None => None,
    };
    if request.id.is_some() && existing.is_none() {
        return Err(AppError::not_found("Saved SQL not found"));
    }
    if existing.is_none() && request.sql.is_none() {
        return Err(AppError::bad_request("SQL is required when creating a saved query"));
    }
    let library = state.app.storage.load_saved_sql_library_summary().await.map_err(AppError::from)?;
    validate_saved_sql_folder_target(&library, &request.connection_id, request.folder_id.as_deref(), None)
        .map_err(AppError::bad_request)?;
    let now = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let trimmed_name = request.name.trim();
    let normalized_name = if trimmed_name.to_ascii_lowercase().ends_with(".sql") {
        trimmed_name.to_string()
    } else {
        format!("{trimmed_name}.sql")
    };
    let file = SavedSqlFile {
        id: request.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
        connection_id: request.connection_id,
        folder_id: request.folder_id,
        name: normalized_name,
        database: request.database,
        schema: request.schema.filter(|schema| !schema.is_empty()),
        sql: request.sql.or_else(|| existing.as_ref().map(|file| file.sql.clone())).unwrap_or_default(),
        sql_loaded: true,
        order_index: existing.as_ref().map_or(0, |file| file.order_index),
        open_count: existing.as_ref().map_or(0, |file| file.open_count),
        opened_at: existing.as_ref().and_then(|file| file.opened_at.clone()),
        created_at: existing.as_ref().map_or_else(|| now.clone(), |file| file.created_at.clone()),
        updated_at: now,
    };
    state.app.storage.save_saved_sql_file(&file).await.map_err(AppError::from)?;
    Ok(Json(file))
}

pub async fn save_saved_sql_folder(
    State(state): State<Arc<WebState>>,
    Json(request): Json<MobileSaveSqlFolderRequest>,
) -> Result<Json<SavedSqlFolder>, AppError> {
    validate_saved_sql_folder_request(&request).map_err(AppError::bad_request)?;
    let connection_exists = state
        .app
        .storage
        .load_connections()
        .await
        .map_err(AppError::from)?
        .iter()
        .any(|config| config.id == request.connection_id);
    if !connection_exists {
        return Err(AppError::not_found("Connection not found"));
    }
    let library = state.app.storage.load_saved_sql_library_summary().await.map_err(AppError::from)?;
    let existing = request.id.as_deref().and_then(|id| library.folders.iter().find(|folder| folder.id == id));
    if request.id.is_some() && existing.is_none() {
        return Err(AppError::not_found("Saved SQL folder not found"));
    }
    if existing.is_some_and(|folder| folder.connection_id != request.connection_id) {
        return Err(AppError::bad_request("A saved SQL folder cannot change connections"));
    }
    validate_saved_sql_folder_target(
        &library,
        &request.connection_id,
        request.parent_folder_id.as_deref(),
        request.id.as_deref(),
    )
    .map_err(AppError::bad_request)?;
    let now = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let folder = SavedSqlFolder {
        id: request.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
        connection_id: request.connection_id,
        parent_folder_id: request.parent_folder_id,
        name: request.name.trim().to_string(),
        order_index: existing.map_or(0, |folder| folder.order_index),
        created_at: existing.map_or_else(|| now.clone(), |folder| folder.created_at.clone()),
        updated_at: now,
    };
    state.app.storage.save_saved_sql_folder(&folder).await.map_err(AppError::from)?;
    Ok(Json(folder))
}

pub async fn delete_saved_sql_folder(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<()>, AppError> {
    if id.is_empty() || id.len() > 128 {
        return Err(AppError::bad_request("Invalid saved SQL folder id"));
    }
    let library = state.app.storage.load_saved_sql_library_summary().await.map_err(AppError::from)?;
    if !library.folders.iter().any(|folder| folder.id == id) {
        return Err(AppError::not_found("Saved SQL folder not found"));
    }
    state.app.storage.delete_saved_sql_folder(&id).await.map_err(AppError::from)?;
    Ok(Json(()))
}

pub async fn delete_saved_sql_file(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<()>, AppError> {
    state.app.storage.delete_saved_sql_file(&id).await.map_err(AppError::from)?;
    Ok(Json(()))
}

#[cfg(test)]
mod tests {
    use dbx_core::history::HistoryEntry;
    use dbx_core::models::connection::DatabaseType;
    use dbx_core::query::ManualTransactionError;
    use dbx_core::query_cancel::RunningQueries;
    use dbx_core::saved_sql::{SavedSqlFolder, SavedSqlLibrary};

    use super::{
        build_mobile_table_sql, ensure_mobile_read_only_sql, ensure_mobile_result_size, mobile_query_error,
        mobile_transaction_error, validate_history_id, validate_mobile_execution_id, validate_mobile_query_page,
        validate_mobile_table_request, validate_saved_sql_folder_request, validate_saved_sql_folder_target,
        validate_saved_sql_request, MobileConnectionSummary, MobileHistoryCursor, MobileHistoryEntry,
        MobileQueryCancellationGuard, MobileQueryRequest, MobileSaveSqlFolderRequest, MobileSaveSqlRequest,
        MobileTableFilter, MobileTableFilterOperator, MobileTableRequest, MobileTableSort, MobileTableSortDirection,
    };

    #[test]
    fn summary_json_does_not_expose_credentials_or_scripts() {
        let summary = MobileConnectionSummary {
            id: "prod".to_string(),
            name: "Production".to_string(),
            note: "Primary".to_string(),
            db_type: DatabaseType::Postgres,
            host: "db.internal".to_string(),
            port: 5432,
            database: Some("app".to_string()),
            color: Some("#ff0000".to_string()),
            ssl: true,
            read_only: true,
            is_production: true,
            connect_timeout_secs: 10,
            query_timeout_secs: 60,
            has_proxy: false,
            has_ca_certificate: false,
        };

        let value = serde_json::to_value(summary).unwrap();
        let object = value.as_object().unwrap();
        assert_eq!(object.get("name").and_then(|value| value.as_str()), Some("Production"));
        for forbidden in ["username", "password", "initScript", "clientKeyPath", "connectionString"] {
            assert!(!object.contains_key(forbidden), "mobile summary leaked {forbidden}");
        }
    }

    #[test]
    fn mobile_query_allows_reads_and_blocks_writes() {
        assert!(ensure_mobile_read_only_sql("WITH x AS (SELECT 1) SELECT * FROM x", DatabaseType::Postgres).is_ok());
        assert!(ensure_mobile_read_only_sql("SELECT 1;", DatabaseType::Postgres).is_ok());
        assert!(ensure_mobile_read_only_sql("SHOW TABLES", DatabaseType::Mysql).is_ok());
        assert!(ensure_mobile_read_only_sql("SELECT TOP 10 * FROM users", DatabaseType::SqlServer).is_ok());
        assert!(ensure_mobile_read_only_sql("UPDATE users SET admin = true", DatabaseType::Postgres).is_err());
        assert!(ensure_mobile_read_only_sql("SELECT * FROM users FOR UPDATE", DatabaseType::Postgres).is_err());
        assert!(ensure_mobile_read_only_sql("SELECT 1; SELECT 2", DatabaseType::Postgres).is_err());
    }

    #[test]
    fn mobile_execution_ids_are_bounded_and_path_safe() {
        assert!(validate_mobile_execution_id("mobile-query_123").is_ok());
        assert!(validate_mobile_execution_id("").is_err());
        assert!(validate_mobile_execution_id("../query").is_err());
        assert!(validate_mobile_execution_id(&"x".repeat(129)).is_err());
    }

    #[test]
    fn dropped_mobile_request_cancels_the_registered_database_task() {
        let running_queries = RunningQueries::default();
        let registered = running_queries.register("mobile-disconnect".to_string());
        let token = registered.token();
        let guard = MobileQueryCancellationGuard::new(running_queries, "mobile-disconnect".to_string());

        drop(guard);

        assert!(token.is_cancelled());
    }

    #[test]
    fn mobile_query_pages_are_bounded() {
        let request = MobileQueryRequest {
            connection_id: "connection-1".to_string(),
            database: "app".to_string(),
            schema: None,
            sql: "SELECT 1".to_string(),
            execution_id: None,
            offset: Some(100),
            page_size: Some(50),
        };
        assert_eq!(validate_mobile_query_page(&request).unwrap(), (100, 50));
        assert!(validate_mobile_query_page(&MobileQueryRequest { page_size: Some(201), ..request }).is_err());
    }

    #[test]
    fn mobile_query_caps_serialized_result_bytes() {
        assert!(ensure_mobile_result_size(&"small").is_ok());
        assert!(ensure_mobile_result_size(&"x".repeat(2 * 1024 * 1024)).is_err());
    }

    #[test]
    fn mobile_table_sql_quotes_metadata_and_orders_pages_by_primary_key() {
        let columns = vec![
            dbx_core::db::ColumnInfo { name: "order\"id".to_string(), is_primary_key: true, ..Default::default() },
            dbx_core::db::ColumnInfo { name: "select".to_string(), ..Default::default() },
        ];
        let (page, template) = build_mobile_table_sql(
            DatabaseType::Postgres,
            Some("sales\"ops"),
            "order\"log",
            &columns,
            50,
            25,
            &[],
            None,
        )
        .unwrap();

        assert_eq!(
            page,
            "SELECT \"order\"\"id\", \"select\" FROM \"sales\"\"ops\".\"order\"\"log\" ORDER BY \"order\"\"id\" ASC LIMIT 26 OFFSET 50;"
        );
        assert!(template.contains("SELECT \"order\"\"id\", \"select\""));
        assert!(template.ends_with("LIMIT 200 OFFSET 0;"));
    }

    #[test]
    fn mobile_table_sql_uses_each_supported_database_dialect() {
        let columns = vec![
            dbx_core::db::ColumnInfo { name: "order_id".to_string(), is_primary_key: true, ..Default::default() },
            dbx_core::db::ColumnInfo { name: "select".to_string(), ..Default::default() },
        ];
        let build = |database_type| {
            build_mobile_table_sql(database_type, Some("sales"), "order", &columns, 20, 10, &[], None).unwrap().0
        };

        assert_eq!(
            build(DatabaseType::Mysql),
            "SELECT `order_id`, `select` FROM `sales`.`order` ORDER BY `order_id` ASC LIMIT 11 OFFSET 20;"
        );
        assert_eq!(
            build(DatabaseType::Sqlite),
            "SELECT \"order_id\", \"select\" FROM \"sales\".\"order\" ORDER BY \"order_id\" ASC LIMIT 11 OFFSET 20;"
        );
        assert_eq!(
            build(DatabaseType::ClickHouse),
            "SELECT `order_id`, `select` FROM `sales`.`order` ORDER BY `order_id` ASC LIMIT 11 OFFSET 20;"
        );
        assert!(build(DatabaseType::SqlServer)
            .contains("FROM [sales].[order] ORDER BY [order_id] ASC OFFSET 20 ROWS FETCH NEXT 11 ROWS ONLY"));
        let oracle = build(DatabaseType::Oracle);
        assert!(oracle.contains("FROM \"sales\".\"order\" ORDER BY \"order_id\" ASC"));
        assert!(oracle.contains("ROWNUM <= 31"));
        assert!(oracle.contains("\"__dbx_row_num\" > 20"));
    }

    #[test]
    fn mobile_table_request_bounds_pagination_and_identifiers() {
        let valid = MobileTableRequest {
            connection_id: "connection-1".to_string(),
            database: "app".to_string(),
            schema: Some("public".to_string()),
            table: "users".to_string(),
            offset: Some(50),
            limit: Some(50),
            filters: vec![],
            sort: None,
        };
        assert_eq!(validate_mobile_table_request(&valid), Ok((50, 50)));
        assert!(validate_mobile_table_request(&MobileTableRequest { limit: Some(51), ..valid.clone() }).is_err());
        assert!(validate_mobile_table_request(&MobileTableRequest { table: String::new(), ..valid.clone() }).is_err());
        assert!(validate_mobile_table_request(&MobileTableRequest { offset: Some(1_000_001), ..valid }).is_err());
    }

    #[test]
    fn mobile_table_sql_validates_fields_and_escapes_filter_values() {
        let columns = vec![
            dbx_core::db::ColumnInfo { name: "id".to_string(), is_primary_key: true, ..Default::default() },
            dbx_core::db::ColumnInfo { name: "display_name".to_string(), ..Default::default() },
        ];
        let filters = vec![MobileTableFilter {
            column: "display_name".to_string(),
            operator: MobileTableFilterOperator::Contains,
            value: "50% O'Reilly_\\staff".to_string(),
        }];
        let sort = MobileTableSort { column: "display_name".to_string(), direction: MobileTableSortDirection::Desc };

        let (page, template) = build_mobile_table_sql(
            DatabaseType::Postgres,
            Some("public"),
            "users",
            &columns,
            0,
            30,
            &filters,
            Some(&sort),
        )
        .unwrap();

        assert!(page.contains("WHERE CAST(\"display_name\" AS TEXT) LIKE '%50!% O''Reilly!_\\staff%' ESCAPE '!'"));
        assert!(page.contains("ORDER BY \"display_name\" DESC, \"id\" ASC LIMIT 31 OFFSET 0"));
        assert!(template.contains("WHERE CAST(\"display_name\" AS TEXT)"));
        assert!(build_mobile_table_sql(
            DatabaseType::Postgres,
            Some("public"),
            "users",
            &columns,
            0,
            30,
            &[MobileTableFilter {
                column: "missing".to_string(),
                operator: MobileTableFilterOperator::Equals,
                value: "x".to_string(),
            }],
            None,
        )
        .is_err());
        assert!(build_mobile_table_sql(
            DatabaseType::Postgres,
            Some("public"),
            "users",
            &columns,
            0,
            30,
            &[],
            Some(&MobileTableSort { column: "missing".to_string(), direction: MobileTableSortDirection::Asc }),
        )
        .is_err());
    }

    #[test]
    fn mobile_history_exposes_schema_without_internal_history_details() {
        let entry = MobileHistoryEntry::from(HistoryEntry {
            id: "history-1".to_string(),
            connection_id: "connection-1".to_string(),
            connection_name: "Primary".to_string(),
            database: "app".to_string(),
            sql: "SELECT 1".to_string(),
            executed_at: "2026-07-28T00:00:00Z".to_string(),
            execution_time_ms: 12,
            success: true,
            error: None,
            activity_kind: "query".to_string(),
            operation: "mobile_read_only_query".to_string(),
            target: "reporting".to_string(),
            affected_rows: Some(0),
            rollback_sql: Some("secret internal rollback".to_string()),
            details_json: Some("{\"internal\":true}".to_string()),
        });
        let value = serde_json::to_value(entry).unwrap();
        assert_eq!(value.get("schema").and_then(|value| value.as_str()), Some("reporting"));
        assert!(value.get("rollbackSql").is_none());
        assert!(value.get("detailsJson").is_none());
    }

    #[test]
    fn mobile_history_cursor_uses_camel_case_and_rejects_invalid_ids() {
        let value = serde_json::to_value(MobileHistoryCursor {
            executed_at: "2026-07-28T00:00:00Z".to_string(),
            id: "history-1".to_string(),
        })
        .unwrap();
        assert_eq!(value.get("executedAt").and_then(|value| value.as_str()), Some("2026-07-28T00:00:00Z"));
        assert!(value.get("executed_at").is_none());
        assert!(validate_history_id("history-1").is_ok());
        assert!(validate_history_id("").is_err());
        assert!(validate_history_id(&"x".repeat(129)).is_err());
    }

    #[test]
    fn saved_sql_mobile_limits_reject_empty_and_oversized_values() {
        let valid = MobileSaveSqlRequest {
            id: None,
            connection_id: "connection-1".to_string(),
            folder_id: None,
            name: "Daily report".to_string(),
            database: "app".to_string(),
            schema: Some("public".to_string()),
            sql: Some("SELECT 1".to_string()),
        };
        assert!(validate_saved_sql_request(&valid).is_ok());
        assert!(validate_saved_sql_request(&MobileSaveSqlRequest { name: String::new(), ..valid }).is_err());
    }

    #[test]
    fn saved_sql_mobile_folder_targets_stay_within_connection_and_reject_cycles() {
        let folder = |id: &str, connection_id: &str, parent_folder_id: Option<&str>| SavedSqlFolder {
            id: id.to_string(),
            connection_id: connection_id.to_string(),
            parent_folder_id: parent_folder_id.map(str::to_string),
            name: id.to_string(),
            order_index: 0,
            created_at: "2026-07-29T00:00:00Z".to_string(),
            updated_at: "2026-07-29T00:00:00Z".to_string(),
        };
        let library = SavedSqlLibrary {
            folders: vec![
                folder("root", "connection-1", None),
                folder("child", "connection-1", Some("root")),
                folder("other", "connection-2", None),
            ],
            files: vec![],
        };

        assert!(validate_saved_sql_folder_target(&library, "connection-1", Some("root"), None).is_ok());
        assert!(validate_saved_sql_folder_target(&library, "connection-1", Some("missing"), None).is_err());
        assert!(validate_saved_sql_folder_target(&library, "connection-1", Some("other"), None).is_err());
        assert!(validate_saved_sql_folder_target(&library, "connection-1", Some("child"), Some("root")).is_err());
        assert!(validate_saved_sql_folder_request(&MobileSaveSqlFolderRequest {
            id: Some("root".to_string()),
            connection_id: "connection-1".to_string(),
            parent_folder_id: Some("root".to_string()),
            name: "Root".to_string(),
        })
        .is_err());
    }

    #[test]
    fn mobile_errors_distinguish_client_input_timeouts_and_server_failures() {
        assert_eq!(
            mobile_transaction_error(ManualTransactionError::timeout("statement timeout")).status,
            axum::http::StatusCode::REQUEST_TIMEOUT
        );
        assert_eq!(
            mobile_transaction_error(ManualTransactionError::query("relation does not exist")).status,
            axum::http::StatusCode::BAD_REQUEST
        );
        assert_eq!(
            mobile_transaction_error(ManualTransactionError::connection("connection closed")).status,
            axum::http::StatusCode::BAD_GATEWAY
        );
        assert_eq!(
            mobile_transaction_error(ManualTransactionError::internal("transaction session missing")).status,
            axum::http::StatusCode::INTERNAL_SERVER_ERROR
        );
        assert_eq!(crate::error::AppError::not_found("missing").status, axum::http::StatusCode::NOT_FOUND);
        assert_eq!(
            crate::error::AppError::payload_too_large("too large").status,
            axum::http::StatusCode::PAYLOAD_TOO_LARGE
        );
        assert_eq!(mobile_query_error("query timed out".to_string()).status, axum::http::StatusCode::REQUEST_TIMEOUT);
        assert_eq!(mobile_query_error("connection closed".to_string()).status, axum::http::StatusCode::BAD_GATEWAY);
        assert_eq!(mobile_query_error("unknown column".to_string()).status, axum::http::StatusCode::BAD_REQUEST);
    }
}
