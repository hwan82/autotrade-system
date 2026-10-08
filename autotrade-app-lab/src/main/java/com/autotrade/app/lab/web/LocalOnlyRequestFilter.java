package com.autotrade.app.lab.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로컬 대시보드 요청 검사(UI-01, D-020 3번, ADR-005 3장). 모든 요청에 적용한다.
 *
 * <ol>
 *   <li>Host 헤더가 루프백 이름(127.0.0.1, localhost, [::1])이 아니면 403. DNS rebinding으로 외부 페이지가 같은 출처처럼
 *       들어오는 것을 막는다.</li>
 *   <li>상태를 바꾸는 요청(GET·HEAD·OPTIONS 외)은 Origin이 있으면 루프백 출처여야 하고, {@code Sec-Fetch-Site}가
 *       cross-site면 거부하며, {@code X-CSRF-Token}이 프로세스 토큰과 같아야 한다.</li>
 *   <li>보안 응답 헤더(CSP, frame 금지, nosniff, referrer 없음)를 붙이고 API 응답은 캐시하지 않는다.</li>
 * </ol>
 * 거절 응답 본문에는 요청 값(헤더, 토큰)을 넣지 않는다.
 */
public final class LocalOnlyRequestFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private final CsrfToken token;

    public LocalOnlyRequestFilter(CsrfToken token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        // HTTP/1.1 요청은 항상 Host가 있다. 없으면(HTTP/1.0) 컨테이너가 정한 서버 이름으로 본다
        String host = request.getHeader("Host");
        if (!isLoopbackHost(host != null ? host : request.getServerName())) {
            reject(response, "HOST", "루프백 주소(127.0.0.1, localhost)로만 접속할 수 있습니다");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            String origin = request.getHeader("Origin");
            if (origin != null && !isLoopbackOrigin(origin)) {
                reject(response, "ORIGIN", "다른 출처에서 온 변경 요청은 받지 않습니다");
                return;
            }
            if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
                reject(response, "ORIGIN", "다른 사이트에서 온 변경 요청은 받지 않습니다");
                return;
            }
            if (!token.matches(request.getHeader(CSRF_HEADER))) {
                reject(response, "CSRF", "화면을 새로 고친 뒤 다시 시도하세요(CSRF 토큰 불일치)");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static boolean isLoopbackHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            return LOOPBACK_HOSTS.contains(host.substring(0, end + 1)) && validPort(host.substring(end + 1));
        }
        int colon = host.indexOf(':');
        String name = colon < 0 ? host : host.substring(0, colon);
        return LOOPBACK_HOSTS.contains(name) && validPort(colon < 0 ? "" : host.substring(colon));
    }

    private static boolean validPort(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (suffix.charAt(0) != ':' || suffix.length() < 2 || suffix.length() > 6) {
            return false;
        }
        return suffix.substring(1).chars().allMatch(Character::isDigit);
    }

    static boolean isLoopbackOrigin(String origin) {
        try {
            URI uri = URI.create(origin.strip());
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                return false;
            }
            return uri.getRawAuthority() != null && uri.getRawPath().isEmpty()
                    && isLoopbackHost(uri.getRawAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /** 프로세스 기동마다 새로 만드는 CSRF 토큰. 같은 출처 화면만 {@code GET /api/session}으로 읽을 수 있다. */
    public static final class CsrfToken {
        private final String value;

        public CsrfToken(String value) {
            if (value == null || value.length() < 32) {
                throw new IllegalArgumentException("CSRF 토큰이 너무 짧습니다");
            }
            this.value = value;
        }

        public String value() {
            return value;
        }

        boolean matches(String candidate) {
            if (candidate == null) {
                return false;
            }
            return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String toString() {
            return "CsrfToken[****]";
        }
    }
}
