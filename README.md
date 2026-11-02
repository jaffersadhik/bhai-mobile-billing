# Bhai Mobile Billing — architecture notes (for future development)

Updated: 2026-11-02 (sales report, no-cache fix, barcode scanning, user types + branches + stock)

## Stack
- Spring Boot 3.5.x, Java 17, MySQL 8.0.16+ (utf8mb4). Single jar, Docker on AWS EC2. Developer works on Windows (D:\hotel\bhai-mobile-billing-springboot\bhai-mobile-billing).
- Package `com.bhai.billing`. **No JPA** — plain JDBC through `core/Db` (`query`, `one`, `update`, `insert`, `batch`, `tx`); `JdbcDb` implementation. Rows come back as `Map<String,Object>` with **lower-case** column labels.
- `config/AppConfig` builds `BillingService(new JdbcDb(ds), ZoneId bmb.zone)`; startup runner calls `service.migrate()` then creates admin/admin123 on empty DB.
- `web/ApiController` — `/api/*`, session token in `X-Auth` header, `run()` maps `ApiException` (status + Tamil message) and `DbException` to `{ "error": ... }`.
- `src/main/resources/db/schema.sql` runs on every start (CREATE TABLE IF NOT EXISTS) — it never alters existing tables. **Column changes for existing DBs go in `BillingService.migrate()`** (`hasColumn` / `hasIndex` checks, then ALTER). MySQL 8 has no `ADD COLUMN IF NOT EXISTS`.
- Production MySQL runs with ONLY_FULL_GROUP_BY — keep GROUP BY queries strict.
- `application.properties` has `spring.web.resources.cache.cachecontrol.no-cache=true` so phones reload index.html after updates.

## Roles (users.user_type)
| type | rights |
|---|---|
| admin | everything; no company of its own; opens a company with `POST /admin/open`; bills go to the company's first branch |
| owner | company-wide reports only (`requireBilling()` refuses data / catalog / bills / stock with 403); branch filter in report |
| sales | billing, catalog, bill history + report of **own branch** (was `customer`; API still maps `customer` → `sales`) |
| stock | sales + stock of own branch |
Helpers in BillingService: `Ctx.admin()/owner()/stock()`, `requireCompany`, `requireBilling`, `admin(auth)`, `userBranch(c,cid)`, `stockBranch(...)`.
Changing a user's type/company/branch deletes their tokens (new rights at next login).

## Multi-company + branch rule
- non-admin → `users.company_id` (+ `users.branch_id` for sales/stock); admin → company opened (stored in `auth_tokens.company_id`).
- Always `String cid = requireCompany(ctx(auth))` (or `requireBilling`) and filter every query by `company_id`. Never accept company id from the browser for company data.
- Every company has ≥1 branch; `ensureBranches(cid)` creates "Main" and backfills users/bills. Called from migrate() and saveCompany/saveUser.
- Branch filter in SQL: `bf(br)` appends ` AND b.branch_id = ?` as the LAST condition; `args(br, ...)` appends the param.

## Tables
companies, branches(company_id, id, name, sort_order), users(admin|owner|sales|stock, company_id, branch_id), auth_tokens, item_groups, items, item_sizes(company_id, item_id, size_label, rate, barcode NULL unique per company, sort_order), bills(id, company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total), bill_lines(company_id, bill_id, line_no, item_id NULL, item_name, item_name_en, size_label, qty, rate, amount), stock(company_id, branch_id, item_id, size_label, qty INT may be negative, updated_at, updated_by).
Indexes: `idx_bills_company_date`, `idx_bills_branch_date (company_id, branch_id, created_at)`. No discount / payment-mode columns yet.
stock has **no FK to item_sizes** on purpose (catalog save deletes and re-inserts sizes); FK to branches cascades.

## Catalog
- `PUT /catalog` replaces the whole catalog (delete groups → cascade → re-insert) with a `version` check (409 on conflict).
- JSON: cats[{id,ta,en,emoji,color}], items[{id,cat,ta,en,sizes:[{size,rate,code?}]}]. `code` = barcode; `cleanCatalog()` validates (printable ASCII 3–40) and refuses duplicates in the company.
- Frontend must preserve `code` whenever it rebuilds sizes (e.g. saveItemEdit).

## Bills and stock
- `POST /bills` lines may carry `id` (catalog item). Server keeps it only if (item, size) exists in the company catalog, stores `bill_lines.item_id`, and reduces `stock` of the user's branch (`INSERT … ON DUPLICATE KEY UPDATE qty = qty - ?`). Custom lines never touch stock.
- `PUT /stock {item, size, qty}` sets, `{item, size, add}` adds/subtracts; whole numbers, |v| ≤ 999999, negative allowed; admin passes `branch`.

## Frontend (`static/index.html`, single file)
- Hash routing via `view()`: none, home, cat, admin, setup, nocompany, report, stock. Owner is always on `report` (catalog not loaded).
- Role helpers: `isAdmin/isOwner/isStock/canEdit/canStock`, `TYPE_LABEL`, `myBranch` (from session.branch).
- `renderAll()` → `renderBrand/renderBar/renderView/renderDock`. Dialogs use the bottom sheet (`showSheet/closeSheet`, `#sheetBody`).
- One global click listener dispatching on button `id` and `data-*` attributes. `api(path, method, body)` helper. CSS tokens: `--leaf`, `--gold`, `--line`, etc. Note `.bar` is the app header class — don't reuse it.
- Cart lives in localStorage per company (`cart` keyed `itemId|size`, `customs` for custom items, which can carry `code`).
- Report state: `repData, repDate, repBranch`; `branchSalesHtml`, `userSalesHtml`, `reportText` (WhatsApp/copy).
- Stock: `stockData{branch, branchName, branches?, map}`, `loadStock`, `renderStock` (filters all/neg/zero/low, search, 📷), `openStockEdit` (Set / ± modes), `stockScan`.
- Admin: user form types sales/stock/owner/admin + branch select (`fillUBranches`); company card 🏬 கிளைகள் → `openBranches`.
- Barcode: `byCode` map built in `buildCatalog()`. `openScanner({title,hint,done,onCode,onClose})` full-screen camera (`#scan`); `onCode` returns true to keep scanning. Native `BarcodeDetector` first, else lazy-loads `vendor/zxing.min.js` (ZXing 0.21.3, Apache-2.0). `scanToCart(code)` adds 1 piece; unknown → `openUnknownCode`. USB/Bluetooth scanners via keyboard-wedge listener. Camera needs HTTPS (`docker-compose.https.yaml`, Caddy).

## API list
- Session: `POST /login {user, pass}`, `GET /me` (includes `branch` for sales/stock), `POST /logout`, `POST /password`
- Company data: `GET /data`, `PUT /catalog`, `GET /bills` (last 100 + today; own branch for sales/stock), `POST /bills`
- Report: `GET /report?date=yyyy-MM-dd&branch=` — scope, branches, today/day/month summaries, growth, projection, trend, hours, top items, sizes, branchSales, userSales, customers, day's bills
- Stock: `GET /stock?branch=`, `PUT /stock`
- Admin: `GET /admin/overview` (companies incl. branches, users incl. branchName), `GET /admin/sales?date=`, `POST /admin/company`, `DELETE /admin/company/{id}`, `POST /admin/user {…, type, companyId, branchId}`, `DELETE /admin/user/{id}`, `POST /admin/open`, `POST /admin/branch {companyId, id?, name}`, `DELETE /admin/branch/{companyId}/{id}` (refused for last branch, branch with users or bills)

## Build / deploy notes
`mvn -DskipTests package` → `target/bhai-mobile-billing.jar`. Run with `DB_URL`, `DB_USER`, `DB_PASS`, `PORT`, `BMB_ZONE`.
Docker: `docker compose up -d --build` (Dockerfile builds from src — without `--build` the old image keeps running). HTTPS: add `-f docker-compose.https.yaml` with `DOMAIN` in .env.
Patches on Windows: extracting the full zip over the folder is simpler than `git apply` (CRLF / already-applied conflicts).

## Ideas noted for later
- Discount and payment mode (cash / UPI / card) columns on bills → would plug into the report.
- Date-range / Excel export of the report.
- Printing barcode labels for items that have no barcode.
- Stock movement log (who changed what, bill-wise reductions) and stock transfer between branches.