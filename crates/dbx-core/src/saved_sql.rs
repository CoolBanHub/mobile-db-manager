use serde::{Deserialize, Serialize};

fn default_sql_loaded() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedSqlFolder {
    pub id: String,
    pub connection_id: String,
    pub parent_folder_id: Option<String>,
    pub name: String,
    #[serde(default)]
    pub order_index: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedSqlFile {
    pub id: String,
    pub connection_id: String,
    pub folder_id: Option<String>,
    pub name: String,
    pub database: String,
    pub schema: Option<String>,
    pub sql: String,
    #[serde(default = "default_sql_loaded")]
    pub sql_loaded: bool,
    #[serde(default)]
    pub order_index: i64,
    #[serde(default)]
    pub open_count: i64,
    pub opened_at: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedSqlLibrary {
    pub folders: Vec<SavedSqlFolder>,
    pub files: Vec<SavedSqlFile>,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SavedSqlSort {
    #[default]
    UpdatedDesc,
    UpdatedAsc,
    NameAsc,
    NameDesc,
    CreatedDesc,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedSqlSearchRequest {
    #[serde(default)]
    pub query: String,
    #[serde(default)]
    pub connection_ids: Vec<String>,
    #[serde(default)]
    pub sort: SavedSqlSort,
    #[serde(default)]
    pub page: u32,
    #[serde(default)]
    pub page_size: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedSqlSearchResult {
    pub files: Vec<SavedSqlFile>,
    pub total: u64,
    pub page: u32,
    pub page_size: u32,
}
