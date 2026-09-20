package com.suretyseven.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Every request gets a correlation id: reused from the inbound
 * X-Correlation-Id header if the caller supplied one (so a broker's own
 * request id can be threaded through our logs), otherwise generated fresh.
 * It's put in MDC so every log line for this request carries it, echoed
 * back on the response, and threaded through to the external Applicant API
 * call and the downstream notification so the whole chain for one
 * application can be grepped by a single id across all three "systems"
 * in this demo.
 *
 * Ordered at -200: after CorsConfig's CorsFilter (HIGHEST_PRECEDENCE /
 * Integer.MIN_VALUE), but before Spring Security's own filter chain
 * (registered around order -100) -- so log lines produced BY OAuth2
 * token/scope validation (e.g. a rejected bearer token) still carry a
 * correlation id, not just log lines from this app's own controller code.
 */
@Component
@Order(-200)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
