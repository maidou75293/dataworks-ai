package com.lab.dataworks.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keycloak OIDC Authorization Code + PKCE login for the platform console.
 *
 * <p>The browser never sees the Keycloak access token: the backend exchanges the
 * authorization code, introspects the token with the <code>mcp-gateway</code>
 * service account, then issues a short-lived one-time ticket that the SPA
 * exchanges for a normal platform session token.</p>
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {

    private static final Logger log = LoggerFactory.getLogger(SsoController.class);
    private static final long STATE_TTL_MS = 10 * 60 * 1000L;
    private static final long TICKET_TTL_MS = 60 * 1000L;

    private final SessionService sessions;
    private final UserService users;
    private final IdentityPayload identity;
    private final RbacService rbac;
    private final ObjectMapper mapper;
    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final String introspectClientId;
    private final String introspectClientSecret;
    private final String platformPublicUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingState> states = new ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public SsoController(SessionService sessions,
                         UserService users,
                         IdentityPayload identity,
                         RbacService rbac,
                         ObjectMapper mapper,
                         @Value("${keycloak.url:}") String keycloakUrl,
                         @Value("${keycloak.realm:dataworks}") String realm,
                         @Value("${keycloak.client-id:dataworks-web}") String clientId,
                         @Value("${keycloak.introspect-client-id:mcp-gateway}") String introspectClientId,
                         @Value("${keycloak.introspect-client-secret:}") String introspectClientSecret,
                         @Value("${platform.public-url:}") String platformPublicUrl) {
        this.sessions = sessions;
        this.users = users;
        this.identity = identity;
        this.rbac = rbac;
        this.mapper = mapper;
        this.keycloakUrl = trimSlash(keycloakUrl);
        this.realm = realm;
        this.clientId = clientId;
        this.introspectClientId = introspectClientId;
        this.introspectClientSecret = introspectClientSecret == null ? "" : introspectClientSecret.trim();
        this.platformPublicUrl = trimSlash(platformPublicUrl);
    }

    private record PendingState(String verifier, String redirectUri, String target, long expiresAt) {
    }

    private record Ticket(String username, long expiresAt) {
    }

    public record SessionRequest(String ticket) {
    }

    @GetMapping("/info")
    public Map<String, Object> info(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("provider", "keycloak");
        out.put("loginUrl", "/api/auth/sso/start");
        // 回调地址和当前访问地址不同源时，SSO 回跳一定失败：前端据此隐藏入口并给提示
        out.put("sameOrigin", sameOrigin(request));
        out.put("publicUrl", platformPublicUrl);
        return out;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String redirect,
                                      HttpServletRequest request) {
        requireEnabled();
        cleanup();
        // 公开接口：不加上限时任何人都能靠反复 /start 把内存撑大
        if (states.size() > 10_000) {
            cleanup();
            if (states.size() > 10_000) {
                throw new ApiException(429, "登录请求过多，请稍后再试");
            }
        }
        String state = randomToken(24);
        String verifier = randomToken(48);
        String callback = callbackUrl(request);
        states.put(state, new PendingState(verifier, callback, safeRedirect(redirect),
                System.currentTimeMillis() + STATE_TTL_MS));
        String url = keycloakUrl + "/realms/" + enc(realm) + "/protocol/openid-connect/auth"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(callback)
                + "&scope=" + enc("openid profile email")
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(base64Url(sha256(verifier)))
                + "&code_challenge_method=S256";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        if (error != null) {
            log.warn("keycloak login rejected: {}", error);
            return fail(error);
        }
        PendingState pending = state == null ? null : states.remove(state);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            log.warn("keycloak callback with unknown or expired state");
            return fail("state_expired");
        }
        if (code == null || code.isBlank()) {
            return fail("missing_code");
        }
        try {
            JsonNode token = postForm(keycloakUrl + "/realms/" + enc(realm)
                            + "/protocol/openid-connect/token",
                    Map.of(
                            "grant_type", "authorization_code",
                            "code", code,
                            "redirect_uri", pending.redirectUri(),
                            "client_id", clientId,
                            "code_verifier", pending.verifier()));
            String accessToken = token.path("access_token").asText("");
            if (accessToken.isBlank()) {
                log.warn("keycloak token exchange returned no access_token");
                return fail("token_exchange_failed");
            }
            JsonNode claims = introspect(accessToken);
            if (!claims.path("active").asBoolean(false)) {
                log.warn("keycloak token introspection returned active=false");
                return fail("inactive_token");
            }
            String username = firstNonBlank(
                    claims.path("preferred_username").asText(""),
                    claims.path("username").asText(""),
                    claims.path("sub").asText(""));
            if (username.isBlank()) {
                return fail("missing_username");
            }
            // 首次 SSO 登录自动开户（默认只读访客）；被禁用或未绑定的账号拒绝
            if (users.ensureSsoUser(username) == null) {
                UserService.Account existing = users.find(username);
                log.warn("keycloak user rejected: {} ({})", username,
                        existing == null ? "invalid" : "not_linked_or_disabled");
                return fail(existing == null ? "user_disabled" : "account_not_linked");
            }
            sessions.audit(username, "LOGIN_SSO", "keycloak", null);
            String ticket = randomToken(24);
            tickets.put(ticket, new Ticket(username, System.currentTimeMillis() + TICKET_TTL_MS));
            String location = "/login?sso_ticket=" + enc(ticket)
                    + "&redirect=" + enc(pending.target());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(location)).build();
        } catch (Exception exc) {
            log.warn("keycloak callback failed: {}", exc.getClass().getSimpleName());
            return fail("sso_failed");
        }
    }

    @PostMapping("/session")
    public Map<String, Object> session(@RequestBody SessionRequest req) {
        if (req == null || req.ticket() == null) {
            throw new ApiException(400, "ticket 不能为空");
        }
        Ticket ticket = tickets.remove(req.ticket());
        if (ticket == null || ticket.expiresAt() < System.currentTimeMillis()) {
            throw new ApiException(401, "SSO 凭证无效或已过期");
        }
        UserService.Account account = users.find(ticket.username());
        if (account == null) {
            throw new ApiException(401, "账号不存在，请联系平台管理员");
        }
        if (!account.active()) {
            throw new ApiException(403, "账号已被禁用，请联系平台管理员");
        }
        // D03：租户被删除后成员关系已清空，不能再签发会话（旧实现回落 default）
        if (!rbac.hasActiveTenant(ticket.username())) {
            throw new ApiException(401, "账号不属于任何有效租户，请联系平台管理员");
        }
        users.touchLogin(ticket.username());
        sessions.cleanup();
        String token = sessions.create(ticket.username());
        sessions.audit(ticket.username(), "LOGIN_SSO_SESSION", "platform", null);
        return identity.login(token, account);
    }

    private JsonNode introspect(String accessToken) throws Exception {
        return postForm(keycloakUrl + "/realms/" + enc(realm)
                        + "/protocol/openid-connect/token/introspect",
                Map.of(
                        "token", accessToken,
                        "client_id", introspectClientId,
                        "client_secret", introspectClientSecret));
    }

    private JsonNode postForm(String url, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("keycloak HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private ResponseEntity<Void> fail(String reason) {
        String location = "/login?sso_error=" + enc(reason == null ? "sso_failed" : reason);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private boolean sameOrigin(HttpServletRequest request) {
        if (platformPublicUrl.isBlank()) {
            return true;
        }
        try {
            String publicHost = java.net.URI.create(platformPublicUrl).getHost();
            if (publicHost == null || publicHost.isBlank()) {
                return true;
            }
            String host = request.getHeader("X-Forwarded-Host");
            if (host == null || host.isBlank()) {
                host = request.getHeader("Host");
            }
            if (host == null || host.isBlank()) {
                host = request.getServerName();
            }
            String bare = host.split(":")[0].trim();
            return bare.equalsIgnoreCase(publicHost);
        } catch (Exception e) {
            return true;
        }
    }

    private String callbackUrl(HttpServletRequest request) {
        // 只有当前访问地址确实就是配置公网地址时才用它；用 IP:30090 访问时按请求 Host 生成，
        // 否则 redirect_uri 会写成另一个域名，Keycloak 回跳必失败（外部 UX 审计 P1-2）。
        if (!platformPublicUrl.isBlank() && sameOrigin(request)) {
            return platformPublicUrl + "/api/auth/sso/callback";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return proto + "://" + host + "/api/auth/sso/callback";
    }

    private static String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return "/home";
        }
        String value = redirect.trim();
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("\\")
                || value.contains("\r") || value.contains("\n")) {
            return "/home";
        }
        return value;
    }

    private boolean enabled() {
        return !keycloakUrl.isBlank() && !realm.isBlank() && !clientId.isBlank()
                && !introspectClientSecret.isBlank();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ApiException(503, "Keycloak SSO 未配置");
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exc) {
            throw new IllegalStateException(exc);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
