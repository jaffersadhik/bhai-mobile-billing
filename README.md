# Bhai Mobile Billing — architecture notes (for future development)

Updated: 2026-11-14 (language switcher, quick add-branch, per-company website address `/s/<name>`, WhatsApp order number, Dry Fruit Palace master-data template, CSV import)

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
- Admin: user form types sales/stock/owner/admin + branch select (`fillUBranches`); company card ➕ கிளை சேர் (`data-addbranch`, opens the branch sheet with the name box focused) and 🏬 கிளைகள் → `openBranches` (rename / delete / add shop or warehouse).
- Barcode: `byCode` map built in `buildCatalog()`. `openScanner({title,hint,done,onCode,onClose})` full-screen camera (`#scan`); `onCode` returns true to keep scanning. Native `BarcodeDetector` first, else lazy-loads `vendor/zxing.min.js` (ZXing 0.21.3, Apache-2.0). `scanToCart(code)` adds 1 piece; unknown → `openUnknownCode`. USB/Bluetooth scanners via keyboard-wedge listener. Camera needs HTTPS (`docker-compose.https.yaml`, Caddy).

## Languages (i18n) — `static/i18n.js` + `static/i18n/*.json`
- Source text in the app is Tamil. `i18n.js` (loaded by `index.html` and `shop.html`) swaps every Tamil phrase for the selected language and shows **English as a small line under it** (selected language is bold). The user picks the language with the 🌐 button on the login screen or in the ☰ menu (`mLang`); the choice is kept in `localStorage` (`bmb_lang`, `bmb_en2` = small English line on/off).
- Packs: `i18n/languages.json` (list + coverage %), `i18n/en.json` (Tamil → English, complete), `i18n/hi|te|kn|ml.json` (Tamil → translation, screens only partly translated; anything missing falls back to English). Tamil is the source, so it has no pack.
- How it works: a `MutationObserver` translates text nodes (wrapped in `<x-i18n data-src="Tamil">`), `placeholder / aria-label / title`, `<option>`, `wa.me` links; `confirm / alert / prompt`, `navigator.share`, clipboard and `window.open(wa.me…)` are wrapped; `I18N.html(str)` translates PDF / print HTML; `I18N.srcText(el)` gives the original Tamil of an element (copy buttons). Server error messages (Tamil) go through the same toast path, so no Java change is needed.
- Item / group names and customer data are NOT translated (they are user data).
- New screen text: just write Tamil in the code as before, then run `python3 i18n-tools/check_i18n.py` → lists phrases without English; add them to `i18n/en.json` (and other packs). It also writes `i18n-tools/missing-<code>.tsv` (work list for translators) and refreshes coverage.
- New language: add an entry to `i18n/languages.json`, create `i18n/<code>.json`, add the Noto Sans font of its script to the Google Fonts link / `--body` in `index.html`.

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

## Company website (online shop) + WhatsApp orders + master data
- **Every company has its own web address**: `https://<domain>/s/<shop address>` (e.g. `/s/dry-fruit-palace`). `companies.shop_slug` (unique, a-z 0-9 `-`) is made from the company name (`CatalogImport.slugBase`, `-2`, `-3` if taken) by `migrate()` for all existing companies and by `saveCompany` for new ones. Owner / admin can change it in **🔗 Online shop link** (`POST /shop-settings {slug, wa}`). `shopCompany(code)` resolves slug **or** the old `shop_code`, so old `shop.html?s=<code>` links still work.
- `web/ShopPageController` serves `static/shop.html` for `GET /s/{slug}` (adds `<base href="/">`; the page reads the slug from the URL path). Public API stays `/api/shop/{slug-or-code}` (+ `/orders`, `/orders/{token}`, `/photo/{id}`).
- Customers order from catalogue + live stock (existing logic: stock per shop / warehouse, max 5 open orders per mobile). The customer's mobile number is mandatory. After ordering, `shop.html` opens WhatsApp with the full order (name, mobile, address, items, total, status link) to `companies.shop_wa` (blank = company phone) — `https://wa.me/91<number>?text=...&raw=1` (`raw=1` = i18n.js keeps the text in Tamil, the shop's language). Browsers may block the automatic open, so the success screen has a **💬 WhatsApp-ல் ஆர்டரை அனுப்பு** button. Orders also appear in the app under 🛒 ஆர்டர்கள் (existing).
- Admin company form: *Online ஆர்டர் WhatsApp எண்* (`shopWa`). Company card: **🌐 Website** (opens the shop), **📦 Master data**.
- **Master data templates**: `static/templates/<id>.csv` + registry `TEMPLATES` in `BillingService` (id, title, path). Only `dryfruits` now (Dry Fruit Palace: 11 groups, 165 items, 502 sizes — nuts, dates, dry fruits, seeds, pulses, oils & ghee, rice & grains, spices, grocery, gift boxes, sweets; **sample prices**, cost = 84 %, wholesale = 95 % of rate). Regenerate with a script that writes the CSV; add a template = add a CSV + one registry row.
  - Load: company form (new company → *Master data* select), **user form** (new user → select; works for the user's company), company card **📦 Master data** (`POST /admin/company/{id}/template {template, replace, stock}`), `GET /admin/templates`. Default opening stock = 100 per size in every *shop* branch (not warehouses), only where no stock row exists; 0 = none. Loading twice never duplicates (items matched by name; ids are SHA-1 of group+name).
  - A template problem never undoes the company / user that was just saved: the response then has `templateError`.
- **CSV import** (☰ → 📥 ஐட்டம் Import; admin / stock): `POST /catalog/import {csv, replace}`; columns `group, group_en, emoji, color, item, item_en, size, rate, wholesale, cost, barcode, stock` (aliases in `CatalogImport.ALIAS`; comma / tab / semicolon; UTF-8). `stock` sets the stock at the user's branch. `replace` = admin only. The sample file is `static/templates/dryfruits.csv`. Logic is in `core/CatalogImport.java` (pure functions: parse → merge into the current catalog → normal `cleanCatalog()` validation → one transaction).


## Count / weight items, rate change, typed qty / weight, barcode typing
- **Item type**: `items.sold_by` = `count` (default) | `weight`; `items.wt_unit` = `kg` | `g` (weight items use exactly one of them). A weight item has ONE size (`1 kg` / `1 g`) and its rate is the price per that unit. Set in the new-item form (🔢 Count / ⚖️ Weight); the unit cannot be changed afterwards. Custom items can also be weight items.
- **Weight on the bill**: the cashier types the weight and picks kg / g (tile and bill sheet). A bill line stores `qty` = portions (1), `rate` = price of that weight, `size_label` = weight text (`250 g`), `bill_lines.wt_g` = grams of one portion, so amount / reports / invoices / profit / cancel keep working unchanged. `cost` per line = cost per unit × weight.
- **Stock of weight items is kept in GRAMS** (`stock.qty`), reduced by `qty × wt_g` on bill / invoice and restored on cancel. Stock screen shows kg/g; the stock report values it per kg/g and leaves it out of "pieces". Weight items are NOT shown on the online shop.
- **Rate change**: `companies.rate_change` (admin → company form → ✏️ checkbox, default OFF). ON: rate boxes appear on each bill line (count: per piece, weight: per kg/g). OFF: the server rejects a catalog line whose rate differs from the catalog (`addBill`; pending lines already accepted are not re-checked).
- **Typed quantity**: the `−  [n]  +` control is an input on tiles, bill lines and the custom form (max 999).
- **Custom item**: always asks the group (`📁`); no groups at all → "பொது (General)" default group (created when saved permanently). Weight custom: weight + kg/g + rate per unit.
- **Barcode typing**: home has a 🔢 box (Enter / திற) and the scanner overlay has its own box. A registered code (case-insensitive) opens its group, highlights the card and (count items) adds 1; weight items put the cursor in the weight box; unknown codes open the link / add dialog.
- **Barcode persistence**: a scanned / typed code linked to an item is saved by `PUT /catalog` → `item_sizes.barcode` (UNIQUE per company; duplicates are rejected by `cleanCatalog` with a message).
- Migration is automatic (`migrate()`): `items.sold_by`, `items.wt_unit`, `bill_lines.wt_g`, `companies.rate_change`.
- **CSV import of weight items**: columns `sold_by` (`count` default | `weight`) and `wt_unit` (`kg` | `g`). Weight row: size is ignored (becomes `1 kg` / `1 g`), `rate` = price per unit, `stock` is in that unit (stored as grams). An existing item's type / unit cannot be changed by CSV (row skipped with a message).
