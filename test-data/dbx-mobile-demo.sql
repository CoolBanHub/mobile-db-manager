PRAGMA foreign_keys = ON;
PRAGMA journal_mode = DELETE;
PRAGMA application_id = 0x4442584D;
PRAGMA user_version = 1;

DROP VIEW IF EXISTS order_overview;
DROP VIEW IF EXISTS customer_value;
DROP TRIGGER IF EXISTS trg_orders_status_audit;
DROP TABLE IF EXISTS order_status_audit;
DROP TABLE IF EXISTS sensor_readings;
DROP TABLE IF EXISTS app_events;
DROP TABLE IF EXISTS documents;
DROP TABLE IF EXISTS binary_samples;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS employees;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS regions;

CREATE TABLE regions (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  timezone TEXT NOT NULL
);

CREATE TABLE customers (
  id INTEGER PRIMARY KEY,
  customer_no TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  email TEXT,
  phone TEXT,
  region_id INTEGER NOT NULL REFERENCES regions(id),
  tier TEXT NOT NULL CHECK (tier IN ('bronze', 'silver', 'gold', 'platinum')),
  credit_limit NUMERIC NOT NULL CHECK (credit_limit >= 0),
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
  preferences_json TEXT NOT NULL CHECK (json_valid(preferences_json)),
  notes TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT
);

CREATE TABLE employees (
  id INTEGER PRIMARY KEY,
  employee_no TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  title TEXT NOT NULL,
  manager_id INTEGER REFERENCES employees(id),
  salary NUMERIC NOT NULL,
  hired_on TEXT NOT NULL,
  remote INTEGER NOT NULL CHECK (remote IN (0, 1))
);

CREATE TABLE categories (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  description TEXT
);

CREATE TABLE products (
  id INTEGER PRIMARY KEY,
  sku TEXT NOT NULL UNIQUE,
  category_id INTEGER NOT NULL REFERENCES categories(id),
  name TEXT NOT NULL,
  description TEXT,
  unit_price NUMERIC NOT NULL CHECK (unit_price >= 0),
  cost_price NUMERIC NOT NULL CHECK (cost_price >= 0),
  weight_kg REAL,
  attributes_json TEXT NOT NULL CHECK (json_valid(attributes_json)),
  discontinued INTEGER NOT NULL DEFAULT 0 CHECK (discontinued IN (0, 1)),
  created_at TEXT NOT NULL
);

CREATE TABLE inventory (
  product_id INTEGER PRIMARY KEY REFERENCES products(id),
  warehouse TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  reorder_level INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE orders (
  id INTEGER PRIMARY KEY,
  order_no TEXT NOT NULL UNIQUE,
  customer_id INTEGER NOT NULL REFERENCES customers(id),
  sales_rep_id INTEGER REFERENCES employees(id),
  status TEXT NOT NULL CHECK (status IN ('draft', 'paid', 'shipped', 'cancelled', 'refunded')),
  currency TEXT NOT NULL CHECK (length(currency) = 3),
  subtotal NUMERIC NOT NULL,
  tax NUMERIC NOT NULL,
  shipping_fee NUMERIC NOT NULL,
  discount NUMERIC NOT NULL DEFAULT 0,
  total NUMERIC NOT NULL,
  shipping_address_json TEXT CHECK (shipping_address_json IS NULL OR json_valid(shipping_address_json)),
  customer_note TEXT,
  ordered_at TEXT NOT NULL,
  shipped_at TEXT,
  version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE order_items (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id INTEGER NOT NULL REFERENCES products(id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price NUMERIC NOT NULL,
  discount_rate REAL NOT NULL DEFAULT 0 CHECK (discount_rate BETWEEN 0 AND 1),
  line_total NUMERIC NOT NULL,
  UNIQUE(order_id, product_id)
);

CREATE TABLE documents (
  id INTEGER PRIMARY KEY,
  document_key TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  metadata_json TEXT NOT NULL CHECK (json_valid(metadata_json)),
  published_at TEXT,
  archived INTEGER NOT NULL CHECK (archived IN (0, 1))
);

CREATE TABLE binary_samples (
  id INTEGER PRIMARY KEY,
  label TEXT NOT NULL,
  payload BLOB,
  checksum_hex TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE app_events (
  id INTEGER PRIMARY KEY,
  customer_id INTEGER REFERENCES customers(id),
  event_type TEXT NOT NULL,
  source TEXT NOT NULL,
  session_id TEXT NOT NULL,
  properties_json TEXT NOT NULL CHECK (json_valid(properties_json)),
  duration_ms INTEGER,
  occurred_at TEXT NOT NULL
);

CREATE TABLE sensor_readings (
  id INTEGER PRIMARY KEY,
  device_id TEXT NOT NULL,
  temperature_c REAL,
  humidity_pct REAL,
  battery_pct INTEGER CHECK (battery_pct BETWEEN 0 AND 100),
  quality TEXT NOT NULL CHECK (quality IN ('good', 'estimated', 'missing')),
  captured_at TEXT NOT NULL
);

CREATE TABLE order_status_audit (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id),
  old_status TEXT NOT NULL,
  new_status TEXT NOT NULL,
  changed_by TEXT NOT NULL,
  changed_at TEXT NOT NULL
);

CREATE INDEX idx_customers_region_tier ON customers(region_id, tier);
CREATE INDEX idx_customers_active_name ON customers(display_name) WHERE active = 1;
CREATE INDEX idx_products_category_price ON products(category_id, unit_price DESC);
CREATE INDEX idx_orders_customer_time ON orders(customer_id, ordered_at DESC);
CREATE INDEX idx_orders_status_time ON orders(status, ordered_at DESC);
CREATE INDEX idx_order_items_product ON order_items(product_id);
CREATE INDEX idx_events_type_time ON app_events(event_type, occurred_at DESC);
CREATE INDEX idx_sensor_device_time ON sensor_readings(device_id, captured_at DESC);

CREATE TRIGGER trg_orders_status_audit
AFTER UPDATE OF status ON orders
WHEN OLD.status <> NEW.status
BEGIN
  INSERT INTO order_status_audit(order_id, old_status, new_status, changed_by, changed_at)
  VALUES (NEW.id, OLD.status, NEW.status, 'seed-trigger', datetime('now'));
END;

INSERT INTO regions(id, code, name, timezone) VALUES
  (1, 'CN-EAST', '华东', 'Asia/Shanghai'),
  (2, 'CN-SOUTH', '华南', 'Asia/Shanghai'),
  (3, 'APAC', '亚太地区', 'Asia/Singapore'),
  (4, 'EU', '欧洲', 'Europe/Berlin'),
  (5, 'NA', '北美', 'America/Los_Angeles');

INSERT INTO categories(id, name, description) VALUES
  (1, '办公设备', '显示器、键盘与桌面配件'),
  (2, '开发工具', '面向工程团队的软件与硬件'),
  (3, '户外用品', '旅行、露营和运动'),
  (4, '食品饮料', '包含常温和冷链商品'),
  (5, '图书音像', '纸质书、电子内容与音频'),
  (6, '智能家居', '传感器、网关和自动化设备'),
  (7, '服装配饰', '多尺码、多颜色商品'),
  (8, '特殊字符', '用于测试 Unicode、引号与 Emoji 🧪');

INSERT INTO employees(id, employee_no, name, title, manager_id, salary, hired_on, remote) VALUES
  (1, 'EMP-0001', '林知远', '总经理', NULL, 680000.00, '2018-03-12', 0);

WITH RECURSIVE seq(n) AS (
  SELECT 2
  UNION ALL SELECT n + 1 FROM seq WHERE n < 30
)
INSERT INTO employees(id, employee_no, name, title, manager_id, salary, hired_on, remote)
SELECT
  n,
  printf('EMP-%04d', n),
  CASE n % 8
    WHEN 0 THEN '陈晨' WHEN 1 THEN 'Alex Morgan' WHEN 2 THEN '佐藤葵'
    WHEN 3 THEN 'Müller' WHEN 4 THEN 'أمل حسن' WHEN 5 THEN 'Иван Петров'
    WHEN 6 THEN 'O''Connor' ELSE '李小满'
  END || ' #' || n,
  CASE n % 5 WHEN 0 THEN '销售经理' WHEN 1 THEN '客户成功' WHEN 2 THEN '数据工程师' WHEN 3 THEN '产品经理' ELSE '运营专员' END,
  CASE WHEN n <= 6 THEN 1 ELSE 2 + (n % 5) END,
  90000 + n * 3750.50,
  date('2018-01-01', printf('+%d days', n * 67)),
  n % 3 = 0
FROM seq;

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 240
)
INSERT INTO customers(
  id, customer_no, display_name, email, phone, region_id, tier,
  credit_limit, active, preferences_json, notes, created_at, last_seen_at
)
SELECT
  n,
  printf('CUS-%06d', n),
  CASE n % 10
    WHEN 0 THEN '上海星云科技'
    WHEN 1 THEN 'Acme & Sons'
    WHEN 2 THEN '株式会社みらい'
    WHEN 3 THEN 'Café München'
    WHEN 4 THEN 'مرحبا للتجارة'
    WHEN 5 THEN 'ООО Север'
    WHEN 6 THEN 'O''Brien Media'
    WHEN 7 THEN 'Emoji Lab 🚀'
    WHEN 8 THEN '换行测试'
    ELSE '普通客户'
  END || ' ' || printf('%03d', n),
  CASE WHEN n % 17 = 0 THEN NULL ELSE printf('customer%03d@example.test', n) END,
  CASE WHEN n % 13 = 0 THEN NULL ELSE printf('+86-138-%04d-%04d', n % 10000, (n * 37) % 10000) END,
  1 + (n % 5),
  CASE n % 4 WHEN 0 THEN 'bronze' WHEN 1 THEN 'silver' WHEN 2 THEN 'gold' ELSE 'platinum' END,
  round(1000 + (n * 791.37) % 200000, 2),
  CASE WHEN n % 19 = 0 THEN 0 ELSE 1 END,
  json_object(
    'language', CASE n % 4 WHEN 0 THEN 'zh-CN' WHEN 1 THEN 'en-US' WHEN 2 THEN 'ja-JP' ELSE 'de-DE' END,
    'newsletter', json(CASE WHEN n % 3 = 0 THEN 'true' ELSE 'false' END),
    'tags', json_array('mobile', CASE WHEN n % 2 = 0 THEN 'vip' ELSE 'standard' END)
  ),
  CASE
    WHEN n % 29 = 0 THEN '包含换行：第一行' || char(10) || '第二行；以及制表符' || char(9) || 'END'
    WHEN n % 23 = 0 THEN '特殊字符 <script>alert("x")</script>，仅作为文本'
    WHEN n % 11 = 0 THEN ''
    ELSE NULL
  END,
  datetime('2022-01-01', printf('+%d hours', n * 53)),
  CASE WHEN n % 9 = 0 THEN NULL ELSE datetime('2025-01-01', printf('+%d minutes', n * 113)) END
FROM seq;

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 120
)
INSERT INTO products(
  id, sku, category_id, name, description, unit_price, cost_price,
  weight_kg, attributes_json, discontinued, created_at
)
SELECT
  n,
  printf('SKU-%03d-%05d', 1 + (n % 8), n),
  1 + (n % 8),
  CASE n % 8
    WHEN 0 THEN '人体工学键盘'
    WHEN 1 THEN 'USB-C 扩展坞'
    WHEN 2 THEN '户外保温杯'
    WHEN 3 THEN '精品咖啡豆'
    WHEN 4 THEN '数据库设计指南'
    WHEN 5 THEN '温湿度传感器'
    WHEN 6 THEN '轻量冲锋衣'
    ELSE '引号“测试”套件 🧪'
  END || ' ' || n,
  CASE WHEN n % 14 = 0 THEN NULL ELSE '用于 DBX 移动端表格、详情和导出测试的商品描述 #' || n END,
  round(9.90 + ((n * 137.17) % 8990), 2),
  round(4.20 + ((n * 83.11) % 4200), 2),
  CASE WHEN n % 12 = 0 THEN NULL ELSE round(0.05 + (n % 45) * 0.37, 3) END,
  json_object(
    'color', CASE n % 5 WHEN 0 THEN '荧光绿' WHEN 1 THEN '午夜黑' WHEN 2 THEN '海盐白' WHEN 3 THEN '朱砂红' ELSE '透明' END,
    'size', CASE n % 4 WHEN 0 THEN 'S' WHEN 1 THEN 'M' WHEN 2 THEN 'L' ELSE 'XL' END,
    'fragile', json(CASE WHEN n % 7 = 0 THEN 'true' ELSE 'false' END)
  ),
  n % 31 = 0,
  datetime('2023-01-01', printf('+%d days', n * 5))
FROM seq;

INSERT INTO inventory(product_id, warehouse, quantity, reorder_level, updated_at)
SELECT
  id,
  CASE id % 4 WHEN 0 THEN '上海-A' WHEN 1 THEN '深圳-B' WHEN 2 THEN '新加坡-C' ELSE '柏林-D' END,
  CASE WHEN id % 27 = 0 THEN 0 ELSE (id * 47) % 500 END,
  10 + (id % 40),
  datetime('2026-01-01', printf('+%d hours', id * 9))
FROM products;

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 1500
)
INSERT INTO orders(
  id, order_no, customer_id, sales_rep_id, status, currency, subtotal,
  tax, shipping_fee, discount, total, shipping_address_json,
  customer_note, ordered_at, shipped_at, version
)
SELECT
  n,
  printf('ORD-%s-%06d', substr('2024', 1, 4), n),
  1 + (n * 17) % 240,
  CASE WHEN n % 23 = 0 THEN NULL ELSE 2 + (n % 29) END,
  CASE n % 10 WHEN 0 THEN 'draft' WHEN 1 THEN 'cancelled' WHEN 2 THEN 'refunded' WHEN 3 THEN 'paid' ELSE 'shipped' END,
  CASE n % 4 WHEN 0 THEN 'CNY' WHEN 1 THEN 'USD' WHEN 2 THEN 'EUR' ELSE 'JPY' END,
  round(25 + ((n * 193.71) % 15000), 2),
  round(((25 + ((n * 193.71) % 15000)) * 0.06), 2),
  CASE WHEN n % 7 = 0 THEN 0 ELSE round(8 + (n % 55) * 1.3, 2) END,
  CASE WHEN n % 9 = 0 THEN round((n % 300) + 0.25, 2) ELSE 0 END,
  round(
    (25 + ((n * 193.71) % 15000))
    + ((25 + ((n * 193.71) % 15000)) * 0.06)
    + CASE WHEN n % 7 = 0 THEN 0 ELSE 8 + (n % 55) * 1.3 END
    - CASE WHEN n % 9 = 0 THEN (n % 300) + 0.25 ELSE 0 END,
    2
  ),
  CASE WHEN n % 41 = 0 THEN NULL ELSE json_object(
    'country', CASE n % 5 WHEN 0 THEN '中国' WHEN 1 THEN 'Singapore' WHEN 2 THEN 'Deutschland' WHEN 3 THEN '日本' ELSE 'USA' END,
    'city', CASE n % 5 WHEN 0 THEN '上海' WHEN 1 THEN 'Singapore' WHEN 2 THEN 'Berlin' WHEN 3 THEN '東京' ELSE 'Seattle' END,
    'line1', printf('测试大道 %d 号', n % 999),
    'postalCode', printf('%06d', n % 1000000)
  ) END,
  CASE WHEN n % 97 = 0 THEN '请在门口等待；联系电话可能为空。📦' WHEN n % 53 = 0 THEN '' ELSE NULL END,
  datetime('2024-01-01', printf('+%d hours', n * 7)),
  CASE WHEN n % 10 BETWEEN 4 AND 9 THEN datetime('2024-01-02', printf('+%d hours', n * 7)) ELSE NULL END,
  1 + (n % 4)
FROM seq;

WITH RECURSIVE item_seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM item_seq WHERE n < 4500
)
INSERT INTO order_items(id, order_id, product_id, quantity, unit_price, discount_rate, line_total)
SELECT
  n,
  1 + ((n - 1) / 3),
  1 + (((n - 1) / 3) * 7 + ((n - 1) % 3) * 31) % 120,
  1 + (n % 5),
  round(9.90 + (((1 + (((n - 1) / 3) * 7 + ((n - 1) % 3) * 31) % 120) * 137.17) % 8990), 2),
  CASE WHEN n % 11 = 0 THEN 0.15 WHEN n % 7 = 0 THEN 0.05 ELSE 0 END,
  round(
    (1 + (n % 5))
    * (9.90 + (((1 + (((n - 1) / 3) * 7 + ((n - 1) % 3) * 31) % 120) * 137.17) % 8990))
    * (1 - CASE WHEN n % 11 = 0 THEN 0.15 WHEN n % 7 = 0 THEN 0.05 ELSE 0 END),
    2
  )
FROM item_seq;

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 80
)
INSERT INTO documents(id, document_key, title, body, metadata_json, published_at, archived)
SELECT
  n,
  printf('doc/%04d', n),
  CASE n % 5 WHEN 0 THEN '发布说明' WHEN 1 THEN '故障复盘' WHEN 2 THEN '产品需求' WHEN 3 THEN '会议纪要' ELSE '多语言文档 🌏' END || ' #' || n,
  printf('这是第 %d 份测试文档。%s', n, CASE WHEN n % 8 = 0 THEN char(10) || '它包含多行文本、"引号"、反斜杠与 Emoji ✅。' ELSE ' 正文用于详情抽屉测试。' END),
  json_object('author', 1 + n % 30, 'labels', json_array('demo', CASE n % 3 WHEN 0 THEN 'urgent' WHEN 1 THEN 'review' ELSE 'normal' END), 'revision', 1 + n % 12),
  CASE WHEN n % 6 = 0 THEN NULL ELSE datetime('2025-06-01', printf('+%d days', n)) END,
  n % 13 = 0
FROM seq;

INSERT INTO binary_samples(id, label, payload, checksum_hex, created_at) VALUES
  (1, 'empty blob', X'', lower(hex(X'')), '2026-01-01T00:00:00Z'),
  (2, 'null payload', NULL, NULL, '2026-01-01T00:01:00Z'),
  (3, 'PNG signature', X'89504E470D0A1A0A', lower(hex(X'89504E470D0A1A0A')), '2026-01-01T00:02:00Z'),
  (4, 'all byte edges', X'00017F80FEFF', lower(hex(X'00017F80FEFF')), '2026-01-01T00:03:00Z');

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 2500
)
INSERT INTO app_events(id, customer_id, event_type, source, session_id, properties_json, duration_ms, occurred_at)
SELECT
  n,
  CASE WHEN n % 37 = 0 THEN NULL ELSE 1 + (n * 13) % 240 END,
  CASE n % 7 WHEN 0 THEN 'login' WHEN 1 THEN 'query_run' WHEN 2 THEN 'table_open' WHEN 3 THEN 'export' WHEN 4 THEN 'filter_apply' WHEN 5 THEN 'sort_change' ELSE 'logout' END,
  CASE n % 3 WHEN 0 THEN 'android' WHEN 1 THEN 'web' ELSE 'desktop' END,
  printf('session-%05d', 1 + ((n - 1) / 8)),
  json_object('screen', CASE n % 4 WHEN 0 THEN 'connections' WHEN 1 THEN 'metadata' WHEN 2 THEN 'query' ELSE 'history' END, 'success', json(CASE WHEN n % 17 = 0 THEN 'false' ELSE 'true' END), 'sequence', n),
  CASE WHEN n % 31 = 0 THEN NULL ELSE (n * 73) % 120000 END,
  datetime('2025-07-01', printf('+%d minutes', n * 11))
FROM seq;

WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL SELECT n + 1 FROM seq WHERE n < 3000
)
INSERT INTO sensor_readings(id, device_id, temperature_c, humidity_pct, battery_pct, quality, captured_at)
SELECT
  n,
  printf('sensor-%02d', 1 + n % 24),
  CASE WHEN n % 89 = 0 THEN NULL ELSE round(-12.5 + (n * 1.731) % 58, 2) END,
  CASE WHEN n % 97 = 0 THEN NULL ELSE round(18 + (n * 2.417) % 79, 2) END,
  CASE WHEN n % 101 = 0 THEN NULL ELSE 100 - (n % 101) END,
  CASE WHEN n % 89 = 0 OR n % 97 = 0 THEN 'missing' WHEN n % 13 = 0 THEN 'estimated' ELSE 'good' END,
  datetime('2026-01-01', printf('+%d minutes', n * 5))
FROM seq;

-- Exercise the audit trigger after the initial bulk load.
UPDATE orders SET status = 'shipped', version = version + 1 WHERE id % 137 = 0 AND status <> 'shipped';

CREATE VIEW order_overview AS
SELECT
  o.id,
  o.order_no,
  c.customer_no,
  c.display_name AS customer_name,
  r.name AS region_name,
  o.status,
  o.currency,
  o.total,
  COUNT(oi.id) AS item_lines,
  SUM(oi.quantity) AS item_quantity,
  o.ordered_at,
  o.shipped_at
FROM orders o
JOIN customers c ON c.id = o.customer_id
JOIN regions r ON r.id = c.region_id
LEFT JOIN order_items oi ON oi.order_id = o.id
GROUP BY o.id;

CREATE VIEW customer_value AS
SELECT
  c.id,
  c.customer_no,
  c.display_name,
  c.tier,
  COUNT(o.id) AS order_count,
  round(COALESCE(SUM(o.total), 0), 2) AS lifetime_value,
  MAX(o.ordered_at) AS last_order_at
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id;

ANALYZE;
PRAGMA optimize;
