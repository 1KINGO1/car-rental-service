package ua.carrental.booking.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String key = CorrelationIdInterceptor.HEADER;
        String previous = MDC.get(key);
        String id = request.getHeader(key);
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(key, id);
        response.setHeader(key, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove(key); else MDC.put(key, previous);
        }
    }
}
