package com.bhai.billing.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Bhai Mobile Billing - all business rules.
 * Company separation: the company is ALWAYS resolved from the login session (never taken from the request),
 * and every company-data query is filtered by that company_id.
 */
public class BillingService {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");
    static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");
    static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z0-9]{13}");
    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{1,8}");
    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    static final String DEFAULT_FOOTER = "நன்றி! மீண்டும் வருக 🙏";
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Db db;
    private final ZoneId zone;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();

    public BillingService(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    /** Session of the calling user. */
    static final class Ctx {
        final String key;
        final Map<String, Object> user;
        String activeCompany;

        Ctx(String key, Map<String, Object> user, String activeCompany) {
            this.key = key;
            this.user = user;
            this.activeCompany = activeCompany;
        }

        boolean admin() { return "admin".equals(str(user.get("user_type"))); }

        String uid() { return str(user.get("id")); }
    }

    // ================================================================ startup
    /** First start: create admin / admin123 (must be changed after login). */
    public boolean ensureDefaultAdmin() {
        Map<String, Object> r = db.one("SELECT COUNT(*) AS n FROM users");
        if (r != null && lng(r.get("n")) > 0) return false;
        byte[] salt = bytes(16);
        LocalDateTime now = now();
        db.update("INSERT INTO users (id, username, full_name, user_type, company_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'admin', NULL, ?, ?, 1, 1, ?, ?)",
                newId("u"), "admin", "Super Admin", b64(salt), hash("admin123", salt), now, now);
        return true;
    }

    // ================================================================ auth
    public Map<String, Object> login(Map<String, Object> in) {
        String u = clip(in.get("user"), 40).toLowerCase(Locale.ROOT);
        String p = rawStr(in.get("pass"));
        if (u.isEmpty() || p.isEmpty()) throw new ApiException(400, "பயனர் பெயர், கடவுச்சொல் தேவை");
        long nowMs = System.currentTimeMillis();
        long[] f = fails.get(u);
        if (f != null && f[1] > nowMs)
            throw new ApiException(429, "பல முறை தவறு. " + ((f[1] - nowMs) / 1000 + 1) + " விநாடி கழித்து முயற்சிக்கவும்");
        Map<String, Object> user = db.one("SELECT * FROM users WHERE username = ?", u);
        boolean good = user != null ? checkPassword(user, p) : dummyCheck(p);
        if (!good) {
            if (fails.size() > 10000) fails.clear();
            long[] g = fails.computeIfAbsent(u, k -> new long[2]);
            synchronized (g) {
                g[0]++;
                if (g[0] >= 5) { g[0] = 0; g[1] = nowMs + 60000; }
            }
            throw new ApiException(401, "தவறான பயனர் பெயர் அல்லது கடவுச்சொல்");
        }
        fails.remove(u);
        if (!bool(user.get("active"))) throw new ApiException(403, "இந்த கணக்கு நிறுத்தப்பட்டுள்ளது. Admin-ஐ தொடர்பு கொள்ளவும்");
        boolean remember = !(in.get("remember") instanceof Boolean) || (Boolean) in.get("remember");
        String raw = hex(bytes(32));
        LocalDateTime now = now();
        db.update("DELETE FROM auth_tokens WHERE expires_at < ?", now);
        db.update("INSERT INTO auth_tokens (token_hash, user_id, company_id, expires_at, created_at) VALUES (?, ?, NULL, ?, ?)",
                sha(raw), user.get("id"), remember ? now.plusDays(30) : now.plusHours(12), now);
        Map<String, Object> out = session(new Ctx(sha(raw), user, null));
        out.put("token", raw);
        return out;
    }

    Ctx ctx(String raw) {
        if (blank(raw)) throw new ApiException(401, "Login தேவை");
        String key = sha(raw.trim());
        Map<String, Object> r = db.one("SELECT t.company_id AS active_company, t.expires_at AS token_expires, u.* "
                + "FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?", key);
        if (r == null || ldt(r.get("token_expires")).isBefore(now()) || !bool(r.get("active"))) {
            if (r != null) db.update("DELETE FROM auth_tokens WHERE token_hash = ?", key);
            throw new ApiException(401, "Session முடிந்தது. மீண்டும் login செய்யவும்");
        }
        return new Ctx(key, r, nz(r.get("active_company")));
    }

    public Map<String, Object> me(String auth) { return session(ctx(auth)); }

    public Map<String, Object> logout(String auth) {
        Ctx c = ctx(auth);
        db.update("DELETE FROM auth_tokens WHERE token_hash = ?", c.key);
        return obj("ok", true);
    }

    public Map<String, Object> password(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String old = rawStr(in.get("old")), nw = rawStr(in.get("new"));
        if (!checkPassword(c.user, old)) throw new ApiException(400, "பழைய கடவுச்சொல் தவறு");
        validPassword(nw);
        byte[] salt = bytes(16);
        db.update("UPDATE users SET pass_salt = ?, pass_hash = ?, must_change = 0, updated_at = ? WHERE id = ?",
                b64(salt), hash(nw, salt), now(), c.uid());
        db.update("DELETE FROM auth_tokens WHERE user_id = ? AND token_hash <> ?", c.uid(), c.key);
        return obj("ok", true);
    }

    private Map<String, Object> session(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("user", pubUser(c.user));
        Map<String, Object> co = companyOf(c);
        o.put("company", co == null ? null : pubCompany(co));
        o.put("defaultPassword", bool(c.user.get("must_change")));
        return o;
    }

    /** customer: own company. admin: the company opened with admin/open. */
    private Map<String, Object> companyOf(Ctx c) {
        String cid = c.admin() ? nz(c.activeCompany) : str(c.user.get("company_id"));
        if (blank(cid)) return null;
        return db.one("SELECT * FROM companies WHERE id = ? AND archived = 0", cid);
    }

    private String requireCompany(Ctx c) {
        Map<String, Object> co = companyOf(c);
        if (co == null) throw new ApiException(403, "கம்பெனி இணைக்கப்படவில்லை (No company)");
        return str(co.get("id"));
    }

    // ================================================================ company data (groups / items)
    public Map<String, Object> data(String auth) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", cid)));
        o.put("catalog", loadCatalog(db, cid));
        return o;
    }

    private Map<String, Object> loadCatalog(Db d, String cid) {
        Map<String, Object> co = d.one("SELECT catalog_version, catalog_setup FROM companies WHERE id = ?", cid);
        List<Object> cats = new ArrayList<>(), items = new ArrayList<>();
        for (Map<String, Object> g : d.query("SELECT * FROM item_groups WHERE company_id = ? ORDER BY sort_order, id", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(g.get("id")));
            n.put("ta", str(g.get("name_ta")));
            n.put("en", str(g.get("name_en")));
            n.put("emoji", str(g.get("emoji")));
            n.put("color", str(g.get("color")));
            cats.add(n);
        }
        Map<String, List<Object>> sizes = new LinkedHashMap<>();
        for (Map<String, Object> z : d.query("SELECT item_id, size_label, rate FROM item_sizes WHERE company_id = ? ORDER BY item_id, sort_order", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("size", str(z.get("size_label")));
            n.put("rate", dbl(z.get("rate")));
            sizes.computeIfAbsent(str(z.get("item_id")), k -> new ArrayList<>()).add(n);
        }
        for (Map<String, Object> x : d.query("SELECT * FROM items WHERE company_id = ? ORDER BY sort_order, id", cid)) {
            List<Object> sz = sizes.get(str(x.get("id")));
            if (sz == null) continue;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(x.get("id")));
            n.put("cat", str(x.get("group_id")));
            n.put("ta", str(x.get("name_ta")));
            n.put("en", str(x.get("name_en")));
            n.put("sizes", sz);
            items.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", lng(co.get("catalog_version")));
        out.put("setup", bool(co.get("catalog_setup")));
        out.put("cats", cats);
        out.put("items", items);
        return out;
    }

    /** Saves the full list of groups + items of the caller's company (optimistic version check). */
    public Map<String, Object> putCatalog(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        long version = lng(in.get("version"));
        CleanCatalog clean = cleanCatalog(in);
        Map<String, Object> cat = db.tx(t -> {
            Map<String, Object> co = t.one("SELECT catalog_version FROM companies WHERE id = ? FOR UPDATE", cid);
            if (co == null || lng(co.get("catalog_version")) != version)
                throw new ApiException(409, "வேறு மொபைலில் மாற்றம் நடந்துள்ளது");
            t.update("DELETE FROM item_groups WHERE company_id = ?", cid);   // cascades to items and item_sizes
            t.batch("INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", clean.groups(cid));
            t.batch("INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order) VALUES (?, ?, ?, ?, ?, ?)", clean.items(cid));
            t.batch("INSERT INTO item_sizes (company_id, item_id, size_label, rate, sort_order) VALUES (?, ?, ?, ?, ?)", clean.sizes(cid));
            t.update("UPDATE companies SET catalog_version = ?, catalog_setup = 1, updated_at = ? WHERE id = ?", version + 1, now(), cid);
            return loadCatalog(t, cid);
        });
        return obj("catalog", cat);
    }

    static final class CleanCatalog {
        final List<Object[]> cats = new ArrayList<>(), items = new ArrayList<>(), sizes = new ArrayList<>();

        List<Object[]> groups(String cid) { return withCompany(cats, cid); }

        List<Object[]> items(String cid) { return withCompany(items, cid); }

        List<Object[]> sizes(String cid) { return withCompany(sizes, cid); }

        private static List<Object[]> withCompany(List<Object[]> rows, String cid) {
            List<Object[]> out = new ArrayList<>();
            for (Object[] r : rows) {
                Object[] n = new Object[r.length + 1];
                n[0] = cid;
                System.arraycopy(r, 0, n, 1, r.length);
                out.add(n);
            }
            return out;
        }
    }

    private CleanCatalog cleanCatalog(Map<String, Object> in) {
        CleanCatalog out = new CleanCatalog();
        Set<String> gids = new HashSet<>(), iids = new HashSet<>();
        for (Object o : list(in.get("cats"))) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = map(o);
            String id = str(g.get("id")), ta = clip(g.get("ta"), 30);
            if (!ID.matcher(id).matches() || gids.contains(id) || ta.isEmpty()) continue;
            if (out.cats.size() >= 100) throw new ApiException(400, "அதிகபட்சம் 100 குரூப்புகள்");
            gids.add(id);
            String em = clip(g.get("emoji"), 16), col = str(g.get("color"));
            out.cats.add(new Object[]{id, ta, clip(g.get("en"), 30), em.isEmpty() ? "🛍️" : em,
                    COLOR.matcher(col).matches() ? col : "#e8eef0", out.cats.size()});
        }
        for (Object o : list(in.get("items"))) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> x = map(o);
            String id = str(x.get("id")), gid = str(x.get("cat")), ta = clip(x.get("ta"), 60);
            if (!ID.matcher(id).matches() || iids.contains(id) || !gids.contains(gid) || ta.isEmpty()) continue;
            List<Object[]> sz = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object zo : list(x.get("sizes"))) {
                if (!(zo instanceof Map)) continue;
                Map<String, Object> z = map(zo);
                String label = clip(z.get("size"), 12);
                double r = round2(dbl(z.get("rate")));
                if (label.isEmpty() || label.indexOf('|') >= 0 || seen.contains(label) || !(r > 0 && r <= 999999)) continue;
                if (sz.size() >= 20) break;
                seen.add(label);
                sz.add(new Object[]{id, label, BigDecimal.valueOf(r), sz.size()});
            }
            if (sz.isEmpty()) continue;
            if (out.items.size() >= 3000) throw new ApiException(400, "அதிகபட்சம் 3000 ஐட்டங்கள்");
            iids.add(id);
            out.items.add(new Object[]{id, gid, ta, clip(x.get("en"), 60), out.items.size()});
            out.sizes.addAll(sz);
        }
        return out;
    }

    // ================================================================ bills
    public Map<String, Object> addBill(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        String cust = clip(in.get("customer"), 60);
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!phone.isEmpty() && !MOBILE.matcher(phone).matches()) throw new ApiException(400, "மொபைல் எண் தவறு");
        List<Object> raw = list(in.get("lines"));
        if (raw.isEmpty()) throw new ApiException(400, "பில்லில் ஐட்டம் இல்லை");
        if (raw.size() > 300) throw new ApiException(400, "அதிக ஐட்டங்கள்");
        List<Map<String, Object>> lines = new ArrayList<>();
        double total = 0;
        long pcs = 0;
        for (Object o : raw) {
            if (!(o instanceof Map)) throw new ApiException(400, "பில் வரி தவறு");
            Map<String, Object> l = map(o);
            String name = clip(l.get("name"), 60);
            double rate = round2(dbl(l.get("rate"))), q = dbl(l.get("qty"));
            if (name.isEmpty() || !(rate > 0 && rate <= 999999) || !(q >= 1 && q <= 999) || q != Math.floor(q))
                throw new ApiException(400, "பில் வரி தவறு: " + name);
            long qty = (long) q;
            double amt = round2(rate * qty);
            total += amt;
            pcs += qty;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", name);
            n.put("en", clip(l.get("en"), 60));
            n.put("size", clip(l.get("size"), 12));
            n.put("qty", qty);
            n.put("rate", rate);
            n.put("amt", amt);
            lines.add(n);
        }
        final double billTotal = round2(total);
        final long pieces = pcs;
        LocalDateTime at = now();
        return db.tx(t -> {
            Map<String, Object> co = t.one("SELECT bill_seq, bill_prefix FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq = lng(co.get("bill_seq")) + 1;
            String prefix = str(co.get("bill_prefix"));
            if (prefix.isEmpty()) prefix = "INV";
            String no = prefix + "-" + String.format("%04d", seq);
            t.update("UPDATE companies SET bill_seq = ? WHERE id = ?", seq, cid);
            long billId = t.insert("INSERT INTO bills (company_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    cid, seq, no, at, cust, phone, str(c.user.get("username")), str(c.user.get("full_name")), pieces, BigDecimal.valueOf(billTotal));
            List<Object[]> rows = new ArrayList<>();
            int ln = 1;
            for (Map<String, Object> l : lines)
                rows.add(new Object[]{cid, billId, ln++, l.get("name"), l.get("en"), l.get("size"), l.get("qty"),
                        BigDecimal.valueOf((Double) l.get("rate")), BigDecimal.valueOf((Double) l.get("amt"))});
            t.batch("INSERT INTO bill_lines (company_id, bill_id, line_no, item_name, item_name_en, size_label, qty, rate, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("no", no);
            b.put("seq", seq);
            b.put("at", iso(at));
            b.put("customer", cust);
            b.put("phone", phone);
            b.put("by", str(c.user.get("full_name")));
            b.put("byUser", str(c.user.get("username")));
            b.put("lines", lines);
            b.put("pieces", pieces);
            b.put("total", billTotal);
            return obj("bill", b);
        });
    }

    public Map<String, Object> bills(String auth) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        List<Map<String, Object>> rows = db.query("SELECT * FROM bills WHERE company_id = ? ORDER BY id DESC LIMIT 100", cid);
        Map<Long, List<Object>> lines = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            StringBuilder in = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(cid);
            for (Map<String, Object> r : rows) {
                in.append(in.length() == 0 ? "?" : ", ?");
                args.add(lng(r.get("id")));
            }
            for (Map<String, Object> l : db.query("SELECT * FROM bill_lines WHERE company_id = ? AND bill_id IN (" + in + ") ORDER BY bill_id, line_no", args.toArray())) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", str(l.get("item_name")));
                n.put("en", str(l.get("item_name_en")));
                n.put("size", str(l.get("size_label")));
                n.put("qty", lng(l.get("qty")));
                n.put("rate", dbl(l.get("rate")));
                n.put("amt", dbl(l.get("amount")));
                lines.computeIfAbsent(lng(l.get("bill_id")), k -> new ArrayList<>()).add(n);
            }
        }
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("no", str(r.get("bill_no")));
            b.put("seq", lng(r.get("bill_seq")));
            b.put("at", iso(ldt(r.get("created_at"))));
            b.put("customer", str(r.get("customer_name")));
            b.put("phone", str(r.get("customer_phone")));
            b.put("by", str(r.get("billed_by_name")));
            b.put("byUser", str(r.get("billed_by_user")));
            b.put("lines", lines.getOrDefault(lng(r.get("id")), new ArrayList<>()));
            b.put("pieces", lng(r.get("pieces")));
            b.put("total", dbl(r.get("total")));
            list.add(b);
        }
        LocalDateTime day = LocalDate.now(zone).atStartOfDay();
        Map<String, Object> td = db.one("SELECT COUNT(*) AS cnt, COALESCE(SUM(total), 0) AS tot FROM bills WHERE company_id = ? AND created_at >= ? AND created_at < ?",
                cid, day, day.plusDays(1));
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("count", lng(td.get("cnt")));
        today.put("total", round2(dbl(td.get("tot"))));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("bills", list);
        o.put("today", today);
        return o;
    }

    // ================================================================ admin
    private Ctx admin(String auth) {
        Ctx c = ctx(auth);
        if (!c.admin()) throw new ApiException(403, "Admin மட்டும் (Admin only)");
        return c;
    }

    public Map<String, Object> overview(String auth) {
        admin(auth);
        List<Object> cs = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT c.*, "
                + "(SELECT COUNT(*) FROM users u WHERE u.company_id = c.id) AS user_count, "
                + "(SELECT COUNT(*) FROM item_groups g WHERE g.company_id = c.id) AS group_count, "
                + "(SELECT COUNT(*) FROM items i WHERE i.company_id = c.id) AS item_count "
                + "FROM companies c WHERE c.archived = 0 ORDER BY c.created_at, c.name")) {
            Map<String, Object> p = pubCompany(r);
            p.put("userCount", lng(r.get("user_count")));
            p.put("groupCount", lng(r.get("group_count")));
            p.put("itemCount", lng(r.get("item_count")));
            p.put("billCount", lng(r.get("bill_seq")));
            cs.add(p);
        }
        List<Object> us = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT u.*, c.name AS company_name FROM users u LEFT JOIN companies c ON c.id = u.company_id ORDER BY u.created_at, u.username")) {
            Map<String, Object> p = pubUser(r);
            p.put("companyName", str(r.get("company_name")));
            us.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companies", cs);
        out.put("users", us);
        return out;
    }

    public Map<String, Object> saveCompany(String auth, Map<String, Object> in) {
        admin(auth);
        String id = str(in.get("id"));
        if (!id.isEmpty() && db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", id) == null)
            throw new ApiException(404, "கம்பெனி இல்லை");
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "கம்பெனி பெயர் தேவை");
        if (db.one("SELECT id FROM companies WHERE archived = 0 AND LOWER(name) = LOWER(?) AND id <> ?", name, id) != null)
            throw new ApiException(409, "இதே பெயரில் கம்பெனி உள்ளது");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (phone.length() > 10) phone = phone.substring(phone.length() - 10);
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String gst = clip(in.get("gstin"), 20).toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (!gst.isEmpty() && !GSTIN.matcher(gst).matches()) throw new ApiException(400, "GSTIN தவறு (15 எழுத்து)");
        String email = clip(in.get("email"), 80);
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw new ApiException(400, "Email தவறு");
        String prefix = clip(in.get("billPrefix"), 8).toUpperCase(Locale.ROOT);
        if (prefix.isEmpty()) prefix = "INV";
        if (!PREFIX.matcher(prefix).matches()) throw new ApiException(400, "Bill prefix: A-Z, 0-9 மட்டும் (max 8)");
        String footer = clip(in.get("footer"), 120);
        boolean bis = !(in.get("bismillah") instanceof Boolean) || (Boolean) in.get("bismillah");
        LocalDateTime now = now();
        Object[] f = {name, clip(in.get("tagline"), 60), clip(in.get("owner"), 60), phone, clip(in.get("address"), 200),
                clip(in.get("city"), 40), gst, email, prefix, bis, footer.isEmpty() ? DEFAULT_FOOTER : footer, now};
        if (id.isEmpty()) {
            id = newId("c");
            db.update("INSERT INTO companies (name, tagline, owner_name, phone, address, city, gstin, email, bill_prefix, show_bismillah, bill_footer, updated_at, id, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", concat(f, id, now));
        } else {
            db.update("UPDATE companies SET name = ?, tagline = ?, owner_name = ?, phone = ?, address = ?, city = ?, gstin = ?, email = ?, "
                    + "bill_prefix = ?, show_bismillah = ?, bill_footer = ?, updated_at = ? WHERE id = ?", concat(f, id));
        }
        return obj("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", id)));
    }

    /** Archives the company (its groups, items and bills stay in the database). */
    public Map<String, Object> deleteCompany(String auth, String id) {
        admin(auth);
        if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", id) == null) throw new ApiException(404, "கம்பெனி இல்லை");
        if (lng(db.one("SELECT COUNT(*) AS n FROM users WHERE company_id = ?", id).get("n")) > 0)
            throw new ApiException(409, "இந்த கம்பெனியில் users உள்ளனர். முதலில் அவர்களை நீக்கவும் அல்லது மாற்றவும்");
        db.update("UPDATE companies SET archived = 1, updated_at = ? WHERE id = ?", now(), id);
        db.update("UPDATE auth_tokens SET company_id = NULL WHERE company_id = ?", id);
        return obj("ok", true);
    }

    public Map<String, Object> saveUser(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String id = str(in.get("id"));
        Map<String, Object> u = null;
        if (!id.isEmpty()) {
            u = db.one("SELECT * FROM users WHERE id = ?", id);
            if (u == null) throw new ApiException(404, "User இல்லை");
        }
        String un = str(in.get("user")).trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(un).matches()) throw new ApiException(400, "Username: 3-30 எழுத்து, a-z 0-9 . _ - மட்டும்");
        Map<String, Object> dup = db.one("SELECT id FROM users WHERE username = ?", un);
        if (dup != null && !str(dup.get("id")).equals(id)) throw new ApiException(409, "இந்த username ஏற்கனவே உள்ளது");
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "பெயர் தேவை");
        String type = str(in.get("type"));
        if (!type.equals("admin") && !type.equals("customer")) throw new ApiException(400, "User type: admin / customer");
        String cid = null;
        if (type.equals("customer")) {
            cid = str(in.get("companyId"));
            if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null)
                throw new ApiException(400, "Customer-க்கு கம்பெனி தேவை");
        }
        boolean active = !(in.get("active") instanceof Boolean) || (Boolean) in.get("active");
        String pw = rawStr(in.get("password"));
        if (u == null && pw.isEmpty()) throw new ApiException(400, "கடவுச்சொல் தேவை");
        if (!pw.isEmpty()) validPassword(pw);
        boolean self = u != null && str(u.get("id")).equals(c.uid());
        if (self && (!active || !type.equals("admin")))
            throw new ApiException(400, "உங்கள் சொந்த admin கணக்கை நிறுத்தவோ type மாற்றவோ முடியாது");
        if (u != null && "admin".equals(str(u.get("user_type"))) && bool(u.get("active")) && (!type.equals("admin") || !active) && activeAdmins() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு Admin இருக்க வேண்டும்");
        LocalDateTime now = now();
        if (u == null) {
            id = newId("u");
            byte[] salt = bytes(16);
            db.update("INSERT INTO users (id, username, full_name, user_type, company_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)", id, un, name, type, cid, b64(salt), hash(pw, salt), active, now, now);
        } else {
            db.update("UPDATE users SET username = ?, full_name = ?, user_type = ?, company_id = ?, active = ?, updated_at = ? WHERE id = ?",
                    un, name, type, cid, active, now, id);
            if (!pw.isEmpty()) {
                byte[] salt = bytes(16);
                db.update("UPDATE users SET pass_salt = ?, pass_hash = ?, must_change = 0 WHERE id = ?", b64(salt), hash(pw, salt), id);
            }
            if (!self && (!pw.isEmpty() || !active)) db.update("DELETE FROM auth_tokens WHERE user_id = ?", id);
        }
        return obj("user", pubUser(db.one("SELECT * FROM users WHERE id = ?", id)));
    }

    public Map<String, Object> deleteUser(String auth, String id) {
        Ctx c = admin(auth);
        Map<String, Object> u = db.one("SELECT * FROM users WHERE id = ?", id);
        if (u == null) throw new ApiException(404, "User இல்லை");
        if (id.equals(c.uid())) throw new ApiException(400, "உங்களை நீங்களே நீக்க முடியாது");
        if ("admin".equals(str(u.get("user_type"))) && bool(u.get("active")) && activeAdmins() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு Admin இருக்க வேண்டும்");
        db.update("DELETE FROM users WHERE id = ?", id);   // tokens are removed by ON DELETE CASCADE
        return obj("ok", true);
    }

    public Map<String, Object> openCompany(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String cid = str(in.get("companyId"));
        if (!cid.isEmpty() && db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null)
            throw new ApiException(404, "கம்பெனி இல்லை");
        db.update("UPDATE auth_tokens SET company_id = ? WHERE token_hash = ?", cid.isEmpty() ? null : cid, c.key);
        c.activeCompany = cid.isEmpty() ? null : cid;
        return session(c);
    }

    private long activeAdmins() {
        return lng(db.one("SELECT COUNT(*) AS n FROM users WHERE user_type = 'admin' AND active = 1").get("n"));
    }

    // ================================================================ output mapping
    private Map<String, Object> pubUser(Map<String, Object> u) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", str(u.get("id")));
        p.put("user", str(u.get("username")));
        p.put("name", str(u.get("full_name")));
        p.put("type", str(u.get("user_type")));
        p.put("companyId", u.get("company_id") == null ? null : str(u.get("company_id")));
        p.put("active", bool(u.get("active")));
        p.put("created", iso(ldt(u.get("created_at"))));
        return p;
    }

    private Map<String, Object> pubCompany(Map<String, Object> c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", str(c.get("id")));
        p.put("name", str(c.get("name")));
        p.put("tagline", str(c.get("tagline")));
        p.put("owner", str(c.get("owner_name")));
        p.put("phone", str(c.get("phone")));
        p.put("address", str(c.get("address")));
        p.put("city", str(c.get("city")));
        p.put("gstin", str(c.get("gstin")));
        p.put("email", str(c.get("email")));
        p.put("billPrefix", str(c.get("bill_prefix")));
        p.put("bismillah", bool(c.get("show_bismillah")));
        String f = str(c.get("bill_footer"));
        p.put("footer", f.isEmpty() ? DEFAULT_FOOTER : f);
        p.put("created", iso(ldt(c.get("created_at"))));
        return p;
    }

    // ================================================================ passwords / crypto
    private static String hash(String pass, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 120000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean checkPassword(Map<String, Object> u, String p) {
        try {
            byte[] salt = Base64.getDecoder().decode(str(u.get("pass_salt")));
            return MessageDigest.isEqual(hash(p, salt).getBytes(StandardCharsets.UTF_8), str(u.get("pass_hash")).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean dummyCheck(String p) {
        hash(p, new byte[16]);
        return false;
    }

    static void validPassword(String p) {
        if (p.length() < 6 || p.length() > 100) throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்து இருக்க வேண்டும்");
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private String newId(String prefix) { return prefix + hex(bytes(6)); }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static String sha(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ helpers
    private LocalDateTime now() { return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS); }

    private String iso(LocalDateTime t) { return t == null ? "" : t.atZone(zone).toOffsetDateTime().format(ISO); }

    static LocalDateTime ldt(Object o) {
        if (o instanceof LocalDateTime) return (LocalDateTime) o;
        if (o instanceof Timestamp) return ((Timestamp) o).toLocalDateTime();
        if (o instanceof String && !((String) o).isEmpty()) return LocalDateTime.parse(((String) o).replace(' ', 'T'));
        return null;
    }

    static Object[] concat(Object[] a, Object... b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    static String nz(Object o) { return o == null ? null : o.toString(); }

    static String rawStr(Object o) {
        String s = o instanceof String ? (String) o : "";
        if (s.length() > 200) throw new ApiException(400, "Too long");
        return s;
    }

    /** trimmed, control characters removed, at most max code points */
    static String clip(Object o, int max) {
        String s = o instanceof String || o instanceof Number ? o.toString() : "";
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replace('\n', ' ').trim();
        if (s.codePointCount(0, s.length()) > max) s = s.substring(0, s.offsetByCodePoints(0, max)).trim();
        return s;
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return "1".equals(str(o)) || "true".equalsIgnoreCase(str(o));
    }

    static long lng(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return 0;
        try { return Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { throw new ApiException(400, "Number expected"); }
    }

    static double dbl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) { try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException e) { return Double.NaN; } }
        return Double.NaN;
    }

    static double round2(double d) { return Math.round(d * 100) / 100.0; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) throw new ApiException(400, "Invalid data");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    static Map<String, Object> obj(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
