package ua.carrental.booking.client;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("clientCorrelationIdInterceptor")
@Profile({"http-client-demo", "mock-remote"})
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        String correlationId = MDC.get(MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(MDC_KEY, correlationId);
        }
        request.getHeaders().set(HEADER, correlationId);
        return execution.execute(request, body);
    }
}
