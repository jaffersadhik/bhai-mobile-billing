-- =====================================================================
-- Bhai Mobile Billing - MySQL schema (MySQL 8.0.16 or newer)
-- Runs automatically at application start (spring.sql.init.mode=always).
-- Every statement is CREATE TABLE IF NOT EXISTS, so re-running is safe.
--
-- Company data separation (multi-tenant):
--   * every business table has company_id NOT NULL
--   * primary keys and foreign keys between business tables include company_id,
--     so an item can only belong to a group of the SAME company, a size only to
--     an item of the SAME company, a bill line only to a bill of the SAME company
--   * the application takes company_id only from the logged-in session (never from
--     the browser) and every query filters by it
--
-- utf8mb4 is required for Tamil text and emoji.
-- =====================================================================

-- ---------------------------------------------------------------------
-- companies : one row per shop / customer company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS companies (
  id               VARCHAR(20)   NOT NULL,
  name             VARCHAR(60)   NOT NULL,
  tagline          VARCHAR(60)   NOT NULL DEFAULT '',
  owner_name       VARCHAR(60)   NOT NULL DEFAULT '',
  phone            CHAR(10)      NOT NULL,
  address          VARCHAR(200)  NOT NULL DEFAULT '',
  city             VARCHAR(40)   NOT NULL DEFAULT '',
  gstin            VARCHAR(15)   NOT NULL DEFAULT '',
  email            VARCHAR(80)   NOT NULL DEFAULT '',
  bill_prefix      VARCHAR(8)    NOT NULL DEFAULT 'INV',
  show_bismillah   TINYINT(1)    NOT NULL DEFAULT 1,
  bill_footer      VARCHAR(120)  NOT NULL DEFAULT '',
  bill_seq         BIGINT        NOT NULL DEFAULT 0,
  catalog_version  BIGINT        NOT NULL DEFAULT 0,
  catalog_setup    TINYINT(1)    NOT NULL DEFAULT 0,
  archived         TINYINT(1)    NOT NULL DEFAULT 0,
  created_at       DATETIME      NOT NULL,
  updated_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_companies_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- users : admin (no company) or customer (exactly one company)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id           VARCHAR(20)  NOT NULL,
  username     VARCHAR(30)  NOT NULL,
  full_name    VARCHAR(60)  NOT NULL,
  user_type    ENUM('admin','customer') NOT NULL,
  company_id   VARCHAR(20)  NULL,
  pass_salt    VARCHAR(44)  NOT NULL,
  pass_hash    VARCHAR(64)  NOT NULL,
  active       TINYINT(1)   NOT NULL DEFAULT 1,
  must_change  TINYINT(1)   NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username),
  KEY idx_users_company (company_id),
  CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id),
  CONSTRAINT chk_users_company CHECK ((user_type = 'admin' AND company_id IS NULL) OR (user_type = 'customer' AND company_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- auth_tokens : login sessions (only the SHA-256 of the token is stored)
--   company_id = company an ADMIN has opened for billing (customers use users.company_id)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_tokens (
  token_hash   CHAR(64)     NOT NULL,
  user_id      VARCHAR(20)  NOT NULL,
  company_id   VARCHAR(20)  NULL,
  expires_at   DATETIME     NOT NULL,
  created_at   DATETIME     NOT NULL,
  PRIMARY KEY (token_hash),
  KEY idx_tokens_user (user_id),
  KEY idx_tokens_expires (expires_at),
  CONSTRAINT fk_tokens_user    FOREIGN KEY (user_id)    REFERENCES users (id)     ON DELETE CASCADE,
  CONSTRAINT fk_tokens_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- item_groups : Nighty, Churidar, Saree ... (per company)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_groups (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  name_ta      VARCHAR(30)  NOT NULL,
  name_en      VARCHAR(30)  NOT NULL DEFAULT '',
  emoji        VARCHAR(16)  NOT NULL DEFAULT '',
  color        CHAR(7)      NOT NULL DEFAULT '#e8eef0',
  sort_order   INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (company_id, id),
  CONSTRAINT fk_groups_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- items : products inside a group (same company enforced by composite FK)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS items (
  company_id   VARCHAR(20)  NOT NULL,
  id           VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  group_id     VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  name_ta      VARCHAR(60)  NOT NULL,
  name_en      VARCHAR(60)  NOT NULL DEFAULT '',
  sort_order   INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (company_id, id),
  KEY idx_items_group (company_id, group_id),
  CONSTRAINT fk_items_group FOREIGN KEY (company_id, group_id) REFERENCES item_groups (company_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- item_sizes : size-wise rate of an item
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_sizes (
  company_id   VARCHAR(20)    NOT NULL,
  item_id      VARCHAR(40)    COLLATE utf8mb4_bin NOT NULL,
  size_label   VARCHAR(12)    COLLATE utf8mb4_bin NOT NULL,
  rate         DECIMAL(10,2)  NOT NULL,
  sort_order   INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (company_id, item_id, size_label),
  CONSTRAINT fk_sizes_item FOREIGN KEY (company_id, item_id) REFERENCES items (company_id, id) ON DELETE CASCADE,
  CONSTRAINT chk_sizes_rate CHECK (rate > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- bills : one row per bill, numbered separately for every company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bills (
  id               BIGINT         NOT NULL AUTO_INCREMENT,
  company_id       VARCHAR(20)    NOT NULL,
  bill_seq         BIGINT         NOT NULL,
  bill_no          VARCHAR(20)    NOT NULL,
  created_at       DATETIME       NOT NULL,
  customer_name    VARCHAR(60)    NOT NULL DEFAULT '',
  customer_phone   VARCHAR(10)    NOT NULL DEFAULT '',
  billed_by_user   VARCHAR(30)    NOT NULL DEFAULT '',
  billed_by_name   VARCHAR(60)    NOT NULL DEFAULT '',
  pieces           INT            NOT NULL,
  total            DECIMAL(12,2)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bills_company_id  (company_id, id),
  UNIQUE KEY uk_bills_company_seq (company_id, bill_seq),
  KEY idx_bills_company_date (company_id, created_at),
  CONSTRAINT fk_bills_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- bill_lines : items of a bill (same company enforced by composite FK)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_lines (
  company_id     VARCHAR(20)    NOT NULL,
  bill_id        BIGINT         NOT NULL,
  line_no        INT            NOT NULL,
  item_name      VARCHAR(60)    NOT NULL,
  item_name_en   VARCHAR(60)    NOT NULL DEFAULT '',
  size_label     VARCHAR(12)    NOT NULL DEFAULT '',
  qty            INT            NOT NULL,
  rate           DECIMAL(10,2)  NOT NULL,
  amount         DECIMAL(12,2)  NOT NULL,
  PRIMARY KEY (bill_id, line_no),
  KEY idx_lines_company_bill (company_id, bill_id),
  CONSTRAINT fk_lines_bill FOREIGN KEY (company_id, bill_id) REFERENCES bills (company_id, id) ON DELETE CASCADE,
  CONSTRAINT chk_lines_qty CHECK (qty BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
