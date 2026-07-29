use std::sync::Arc;

use axum::extract::{Path, State};
use axum::Json;
use dbx_core::saved_sql::{SavedSqlFile, SavedSqlFolder, SavedSqlLibrary, SavedSqlSearchRequest, SavedSqlSearchResult};

use crate::error::AppError;
use crate::state::WebState;

pub async fn load_saved_sql_library(State(state): State<Arc<WebState>>) -> Result<Json<SavedSqlLibrary>, AppError> {
    let library = state.app.storage.load_saved_sql_library_summary().await.map_err(AppError::from)?;
    Ok(Json(library))
}

pub async fn load_saved_sql_file(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<Option<SavedSqlFile>>, AppError> {
    let file = state.app.storage.load_saved_sql_file(&id).await.map_err(AppError::from)?;
    Ok(Json(file))
}

pub async fn search_saved_sql_files(
    State(state): State<Arc<WebState>>,
    Json(request): Json<SavedSqlSearchRequest>,
) -> Result<Json<SavedSqlSearchResult>, AppError> {
    let result = state.app.storage.search_saved_sql_files(request).await.map_err(AppError::from)?;
    Ok(Json(result))
}

pub async fn save_saved_sql_folder(
    State(state): State<Arc<WebState>>,
    Json(folder): Json<SavedSqlFolder>,
) -> Result<Json<SavedSqlFolder>, AppError> {
    state.app.storage.save_saved_sql_folder(&folder).await.map_err(AppError::from)?;
    Ok(Json(folder))
}

pub async fn delete_saved_sql_folder(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
) -> Result<Json<()>, AppError> {
    state.app.storage.delete_saved_sql_folder(&id).await.map_err(AppError::from)?;
    Ok(Json(()))
}

pub async fn save_saved_sql_file(
    State(state): State<Arc<WebState>>,
    Json(file): Json<SavedSqlFile>,
) -> Result<Json<SavedSqlFile>, AppError> {
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
