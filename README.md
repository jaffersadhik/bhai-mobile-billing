# Bhai Mobile Billing — Spring Boot + MySQL

Standalone Spring Boot application (embedded Tomcat). One jar, `java -jar`.
Admin manages companies and users; each customer company has its own groups,
items, bill numbers and bill history, kept separate in MySQL by `company_id`.

## Requirements
- JDK 17 or newer, Maven 3.9+
- MySQL 8.0.16 or newer (CHECK constraints are enforced from 8.0.16)

## 1. Create the database (once)
```bash
# edit the password inside the file first
mysql -u root -p < db/create-database.sql
```

## 2. Build
```bash
mvn -DskipTests package
# -> target/bhai-mobile-billing.jar
```

## 3. Run
```bash
export DB_URL='jdbc:mysql://localhost:3306/bmb?useUnicode=true&characterEncoding=UTF-8&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=bmb
export DB_PASS='Change_Me_123'
java -jar target/bhai-mobile-billing.jar          # http://server:8080/
```
At start-up `src/main/resources/db/schema.sql` runs automatically and creates all tables
(`CREATE TABLE IF NOT EXISTS` — safe on every restart). You can also run it by hand:
`mysql -u bmb -p bmb < src/main/resources/db/schema.sql`

First start creates the login **admin / admin123** — change it right after the first login.

Settings (environment variables): `DB_URL`, `DB_USER`, `DB_PASS`, `PORT` (default 8080), `BMB_ZONE` (default Asia/Kolkata).

## Run as a Linux service (optional)
`/etc/systemd/system/bmb.service`
```ini
[Unit]
Description=Bhai Mobile Billing
After=network.target mysql.service

[Service]
User=bmb
WorkingDirectory=/opt/bmb
Environment=DB_USER=bmb
Environment=DB_PASS=Change_Me_123
Environment=PORT=8080
ExecStart=/usr/bin/java -jar /opt/bmb/bhai-mobile-billing.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
`sudo systemctl enable --now bmb` — put Nginx/Apache with HTTPS in front of port 8080.

## Tables (src/main/resources/db/schema.sql)
| Table | Holds | Company separation |
|---|---|---|
| `companies` | shop details, bill prefix, bill counter, catalog version | the tenant itself |
| `users` | admin / customer logins (PBKDF2 password hash) | customer → exactly one `company_id` (CHECK) |
| `auth_tokens` | login sessions (SHA-256 of token only) | admin's opened company |
| `item_groups` | Nighty, Saree … | PK `(company_id, id)` |
| `items` | products | FK `(company_id, group_id)` → same company's group |
| `item_sizes` | size-wise rate | FK `(company_id, item_id)` → same company's item |
| `bills` | bill header, per-company `bill_seq` | UNIQUE `(company_id, bill_seq)` |
| `bill_lines` | bill items | FK `(company_id, bill_id)` → same company's bill |

The application never accepts `company_id` from the browser: it is taken from the login
session (customer → own company, admin → company opened in the Admin Panel), and every
query on company data filters by it. Deleting a company archives it (`archived = 1`);
its bills stay in the database.

## Backup
```bash
mysqldump -u bmb -p --single-transaction bmb > bmb-$(date +%F).sql
```

## Project layout
```
pom.xml
db/create-database.sql
src/main/java/com/bhai/billing/BillingApplication.java     Spring Boot entry point
src/main/java/com/bhai/billing/config/AppConfig.java        beans + default admin on first start
src/main/java/com/bhai/billing/web/ApiController.java       REST API  /api/*
src/main/java/com/bhai/billing/core/BillingService.java     all business rules + SQL
src/main/java/com/bhai/billing/core/JdbcDb.java             JDBC access (HikariCP pool)
src/main/resources/application.properties
src/main/resources/db/schema.sql                            MySQL DDL
src/main/resources/static/index.html                        the app page
```
"# bhai-mobile-billing" 
"# bhai-mobile-billing" 
"# bhai-mobile-billing" 
"# bhai-mobile-billing" 
