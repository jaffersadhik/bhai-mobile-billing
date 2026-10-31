package com.bhai.billing.web;

import com.bhai.billing.core.ApiException;
import com.bhai.billing.core.BillingService;
import com.bhai.billing.core.DbException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API used by the app page (static/index.html). The session token comes in the X-Auth header. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final String AUTH = "X-Auth";
    private final BillingService svc;

    public ApiController(BillingService svc) {
        this.svc = svc;
    }

    // ---- session
    @GetMapping("/ping")
    public ResponseEntity<Object> ping() { return run(() -> Collections.singletonMap("ok", true)); }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.login(nn(body))); }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.me(auth)); }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.logout(auth)); }

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.password(auth, nn(body))); }

    // ---- company data (company always taken from the session)
    @GetMapping("/data")
    public ResponseEntity<Object> data(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.data(auth)); }

    @PutMapping("/catalog")
    public ResponseEntity<Object> catalog(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.putCatalog(auth, nn(body))); }

    @GetMapping("/bills")
    public ResponseEntity<Object> bills(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.bills(auth)); }

    @PostMapping("/bills")
    public ResponseEntity<Object> addBill(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.addBill(auth, nn(body))); }

    // ---- admin
    @GetMapping("/admin/overview")
    public ResponseEntity<Object> overview(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.overview(auth)); }

    @PostMapping("/admin/company")
    public ResponseEntity<Object> saveCompany(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveCompany(auth, nn(body))); }

    @DeleteMapping("/admin/company/{id}")
    public ResponseEntity<Object> deleteCompany(@RequestHeader(value = AUTH, required = false) String auth,
                                                @PathVariable("id") String id) { return run(() -> svc.deleteCompany(auth, id)); }

    @PostMapping("/admin/user")
    public ResponseEntity<Object> saveUser(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveUser(auth, nn(body))); }

    @DeleteMapping("/admin/user/{id}")
    public ResponseEntity<Object> deleteUser(@RequestHeader(value = AUTH, required = false) String auth,
                                             @PathVariable("id") String id) { return run(() -> svc.deleteUser(auth, id)); }

    @PostMapping("/admin/open")
    public ResponseEntity<Object> open(@RequestHeader(value = AUTH, required = false) String auth,
                                       @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.openCompany(auth, nn(body))); }

    // ---- helpers
    private static Map<String, Object> nn(Map<String, Object> body) { return body == null ? new LinkedHashMap<>() : body; }

    private static ResponseEntity<Object> run(Supplier<Object> work) {
        try {
            return ResponseEntity.status(200).header("Cache-Control", "no-store").body(work.get());
        } catch (ApiException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (DbException e) {
            if (e.isConstraintViolation()) return error(409, "தரவு முரண்பாடு — மீண்டும் முயற்சிக்கவும் (conflict)");
            log.error("Database error", e);
            return error(500, "Database error");
        } catch (RuntimeException e) {
            log.error("Server error", e);
            return error(500, "Server error");
        }
    }

    private static ResponseEntity<Object> error(int status, String msg) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Collections.singletonMap("error", msg));
    }
}
