use std::sync::Arc;

use axum::extract::State;
use axum::Json;
use dbx_core::models::connection::{ConnectionConfig, DatabaseType};
use serde::Serialize;

use crate::error::AppError;
use crate::state::WebState;

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

#[cfg(test)]
mod tests {
    use dbx_core::models::connection::DatabaseType;

    use super::MobileConnectionSummary;

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
}
