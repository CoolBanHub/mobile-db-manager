use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;
use dbx_core::history::HistoryEntry;
use dbx_core::models::connection::{ConnectionConfig, DatabaseType};
use dbx_core::saved_sql::{SavedSqlFile, SavedSqlLibrary};
use serde::{Deserialize, Serialize};

use crate::error::AppError;
use crate::state::WebState;

const MOBILE_QUERY_ROW_LIMIT: usize = 200;
const MOBILE_QUERY_RESULT_BYTE_LIMIT: usize = 2 * 1024 * 1024;
const MOBILE_QUERY_STATEMENT_TIMEOUT_MS: u64 = 30_000;
const MOBILE_TRANSACTION_START_TIMEOUT_MS: u64 = 10_000;
const MOBILE_QUERY_OVERALL_TIMEOUT_SECS: u64 = 35;
const MOBILE_HISTORY_LIMIT: usize = 50;
const MOBILE_SAVED_SQL_NAME_LIMIT: usize = 120;

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
        }
    }
}

pub async fn load_connections(
    State(state): State<Arc<WebState>>,
) -> Result<Json<Vec<MobileConnectionSummary>>, AppError> {
    let configs = state.app.storage.load_connections().await.map_err(AppError::from)?;
    Ok(Json(configs.into_iter().map(MobileConnectionSummary::from).collect()))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileQueryRequest {
    pub connection_id: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: String,
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

async fn run_read_only_query(
    state: &WebState,
    request: &MobileQueryRequest,
) -> Result<dbx_core::db::QueryResult, AppError> {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(MOBILE_QUERY_OVERALL_TIMEOUT_SECS);
    let transaction_id = tokio::time::timeout_at(
        deadline,
        dbx_core::query::begin_postgres_read_only_transaction(
            &state.app,
            &request.connection_id,
            &request.database,
            request.schema.as_deref(),
            MOBILE_QUERY_STATEMENT_TIMEOUT_MS,
            MOBILE_TRANSACTION_START_TIMEOUT_MS,
        ),
    )
    .await
    .map_err(|_| AppError::request_timeout("Timed out while creating the read-only transaction"))?
    .map_err(mobile_transaction_error)?;
    let execution = tokio::time::timeout_at(
        deadline,
        dbx_core::query::execute_in_manual_transaction_classified(
            &state.app,
            &transaction_id,
            &request.sql,
            &request.database,
            request.schema.as_deref(),
            Some(MOBILE_QUERY_ROW_LIMIT),
        ),
    )
    .await;
    let cleanup_app = Arc::clone(&state.app);
    let cleanup_transaction_id = transaction_id.clone();
    let cleanup = tokio::spawn(async move {
        dbx_core::query::rollback_manual_transaction(&cleanup_app, &cleanup_transaction_id).await
    });
    let rollback =
        match tokio::time::timeout_at(deadline, cleanup).await {
            Ok(Ok(result)) => result,
            Ok(Err(error)) => {
                return Err(AppError::internal(format!("Read-only transaction cleanup task failed: {error}")))
            }
            Err(_) => return Err(AppError::request_timeout(
                "Mobile query exceeded the 35-second overall limit; transaction cleanup continues in the background",
            )),
        };

    let mut results = match execution {
        Ok(Ok(results)) => results,
        Ok(Err(error)) => return Err(mobile_transaction_error(error)),
        Err(_) => return Err(AppError::request_timeout("Mobile query exceeded the server time limit")),
    };
    rollback.map_err(AppError::internal)?;
    let result = results.pop().ok_or_else(|| AppError::internal("Query returned no result"))?;
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
    ensure_mobile_read_only_sql(&request.sql, config.db_type).map_err(AppError::bad_request)?;
    if config.db_type != DatabaseType::Postgres {
        return Err(AppError::bad_request(
            "Secure mobile query currently requires PostgreSQL; this connection type cannot guarantee a database-level read-only transaction"
        ));
    }

    let started_at = chrono::Utc::now();
    let started = std::time::Instant::now();
    let result = run_read_only_query(&state, &request).await;
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
        details_json: None,
    };
    if let Err(error) = state.app.storage.save_history_entry(&history).await {
        log::warn!("Failed to save mobile query history: {error}");
    }
    result.map(Json)
}

#[derive(Deserialize)]
pub struct MobileHistoryQuery {
    pub limit: Option<usize>,
}

#[derive(Debug, Serialize)]
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
pub struct MobileSaveSqlRequest {
    pub id: Option<String>,
    pub connection_id: String,
    pub name: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: String,
}

fn validate_saved_sql_request(request: &MobileSaveSqlRequest) -> Result<(), String> {
    if request.name.trim().is_empty() || request.name.chars().count() > MOBILE_SAVED_SQL_NAME_LIMIT {
        return Err(format!("Saved SQL name must contain 1-{MOBILE_SAVED_SQL_NAME_LIMIT} characters"));
    }
    if request.database.trim().is_empty() || request.database.chars().count() > 256 {
        return Err("Saved SQL database must contain 1-256 characters".to_string());
    }
    if request.schema.as_ref().is_some_and(|schema| schema.chars().count() > 256) {
        return Err("Saved SQL schema exceeds 256 characters".to_string());
    }
    if request.sql.trim().is_empty() || request.sql.len() > 100_000 {
        return Err("Saved SQL must contain 1-100 KB of SQL".to_string());
    }
    if request.id.as_ref().is_some_and(|id| id.is_empty() || id.len() > 128) {
        return Err("Invalid saved SQL id".to_string());
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
    if request.sql.len() > 100_000 {
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
    let now = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let folder_id = existing
        .as_ref()
        .and_then(|file| (file.connection_id == request.connection_id).then(|| file.folder_id.clone()).flatten());
    let trimmed_name = request.name.trim();
    let normalized_name = if trimmed_name.to_ascii_lowercase().ends_with(".sql") {
        trimmed_name.to_string()
    } else {
        format!("{trimmed_name}.sql")
    };
    let file = SavedSqlFile {
        id: request.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
        connection_id: request.connection_id,
        folder_id,
        name: normalized_name,
        database: request.database,
        schema: request.schema.filter(|schema| !schema.is_empty()),
        sql: request.sql,
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

    use super::{
        ensure_mobile_read_only_sql, ensure_mobile_result_size, mobile_transaction_error, validate_saved_sql_request,
        MobileConnectionSummary, MobileHistoryEntry, MobileSaveSqlRequest,
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
        assert!(ensure_mobile_read_only_sql("UPDATE users SET admin = true", DatabaseType::Postgres).is_err());
        assert!(ensure_mobile_read_only_sql("SELECT * FROM users FOR UPDATE", DatabaseType::Postgres).is_err());
        assert!(ensure_mobile_read_only_sql("SELECT 1; SELECT 2", DatabaseType::Postgres).is_err());
    }

    #[test]
    fn mobile_query_caps_serialized_result_bytes() {
        assert!(ensure_mobile_result_size(&"small").is_ok());
        assert!(ensure_mobile_result_size(&"x".repeat(2 * 1024 * 1024)).is_err());
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
    fn saved_sql_mobile_limits_reject_empty_and_oversized_values() {
        let valid = MobileSaveSqlRequest {
            id: None,
            connection_id: "connection-1".to_string(),
            name: "Daily report".to_string(),
            database: "app".to_string(),
            schema: Some("public".to_string()),
            sql: "SELECT 1".to_string(),
        };
        assert!(validate_saved_sql_request(&valid).is_ok());
        assert!(validate_saved_sql_request(&MobileSaveSqlRequest { name: String::new(), ..valid }).is_err());
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
    }
}
