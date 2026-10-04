package com.paytm.assignment.config;

import com.paytm.assignment.constant.ApiConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestedId = request.getHeader(ApiConstants.REQUEST_ID_HEADER);
        String requestId = requestedId != null && SAFE_REQUEST_ID.matcher(requestedId).matches() ? requestedId : UUID.randomUUID().toString();

        MDC.put(ApiConstants.REQUEST_ID_HEADER, requestId);
        MDC.put("traceId", requestId);
        response.setHeader(ApiConstants.REQUEST_ID_HEADER, requestId);
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("http_request_completed method={} path={} status={} duration_ms={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove("traceId");
            MDC.remove(ApiConstants.REQUEST_ID_HEADER);
        }
    }
}
