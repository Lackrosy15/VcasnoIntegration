package ua.vcasno.integration;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final byte[] webhookToken;
    public ApiKeyFilter(Settings settings) {
        expected = settings.apiKey().getBytes(StandardCharsets.UTF_8);
        webhookToken = settings.webhookToken() == null ? new byte[0] : settings.webhookToken().getBytes(StandardCharsets.UTF_8);
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals("/health") && request.getMethod().equals("GET")) { chain.doFilter(request, response); return; }
        if (request.getRequestURI().matches("/webhooks/kommo/(full|prepayment|postpayment)")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            String[] keys = request.getParameterValues("key");
            if (webhookToken.length < 32 || keys == null || keys.length != 1
                    || !MessageDigest.isEqual(webhookToken, keys[0].getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401); response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\"}");
                return;
            }
            if (!request.getMethod().equals("GET") && !request.getMethod().equals("POST")) {
                response.setStatus(405); response.setHeader("Allow", "GET, POST"); return;
            }
            chain.doFilter(request, response); return;
        }
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid X-API-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
