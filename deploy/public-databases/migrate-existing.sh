#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
set -a
source .env
set +a

backup_dir="backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"

# 迁移前先落盘逻辑备份，失败时可从本次时间戳目录恢复。
echo "Migrating MySQL application databases from new-mysql..."
mysql_databases="$(
  docker exec new-mysql sh -lc \
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW DATABASES"' |
    grep -Ev '^(information_schema|mysql|performance_schema|sys)$' |
    paste -sd' ' -
)"
if [[ -n "$mysql_databases" ]]; then
  docker exec new-mysql sh -lc \
    "exec mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --events --triggers --databases $mysql_databases" \
    >"$backup_dir/mysql.sql"
  docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" -i dbx-public-mysql \
    mysql -uroot <"$backup_dir/mysql.sql"
fi

echo "Migrating PostgreSQL databases from cp-matcher-postgres..."
docker exec cp-matcher-postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select datname from pg_database where not datistemplate and datname <> '\''postgres'\'' order by 1"' |
while IFS= read -r database; do
  [[ -n "$database" ]] || continue
  dump_file="$backup_dir/postgres-${database}.sql"
  docker exec cp-matcher-postgres sh -lc \
    "exec pg_dump -U \"\$POSTGRES_USER\" --clean --if-exists --no-owner --no-privileges --dbname=\"$database\"" \
    >"$dump_file"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" dbx-public-postgresql \
    psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$database' AND pid <> pg_backend_pid();" \
    -c "DROP DATABASE IF EXISTS \"$database\";" \
    -c "CREATE DATABASE \"$database\";"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" -i dbx-public-postgresql \
    psql -U "$POSTGRES_USER" -d "$database" -v ON_ERROR_STOP=1 <"$dump_file"
done

echo "Replicating Redis from cp-matcher-redis..."
# 临时跟随旧实例完成全量同步，确认链路正常后立即提升为独立主库。
  docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" dbx-internet-redis \
  redis-cli REPLICAOF host.docker.internal 6379 >/dev/null
for _ in {1..60}; do
  sync_status="$(
    docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" dbx-internet-redis \
      redis-cli INFO replication | tr -d '\r' | grep '^master_sync_in_progress:' || true
  )"
  link_status="$(
    docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" dbx-internet-redis \
      redis-cli INFO replication | tr -d '\r' | grep '^master_link_status:' || true
  )"
  if [[ "$sync_status" == "master_sync_in_progress:0" && "$link_status" == "master_link_status:up" ]]; then
    break
  fi
  sleep 1
done
docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" dbx-internet-redis \
  redis-cli REPLICAOF NO ONE >/dev/null
docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" dbx-internet-redis \
  redis-cli BGREWRITEAOF >/dev/null

echo "Migration complete. Backups saved under $backup_dir."
