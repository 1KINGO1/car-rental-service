package ua.carrental.booking.web;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;
import org.springframework.stereotype.Component;

@Component
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {
    public static final String HEADER = "X-Correlation-Id";
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String id = MDC.get(HEADER);
        request.getHeaders().set(HEADER, id == null ? UUID.randomUUID().toString() : id);
        return execution.execute(request, body);
    }
}
