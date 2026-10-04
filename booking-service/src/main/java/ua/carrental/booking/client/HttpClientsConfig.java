package ua.carrental.booking.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@Profile({"http-client-demo", "mock-remote"})
public class HttpClientsConfig {

    @Bean
    JdkClientHttpRequestFactory jdkClientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return requestFactory;
    }

    @Bean
    CustomerClient demoCustomerClient(
            JdkClientHttpRequestFactory requestFactory,
            CorrelationIdInterceptor correlationIdInterceptor,
            @Value("${CUSTOMER_URL}") String customerUrl
    ) {
        return createClient(CustomerClient.class, customerUrl, requestFactory, correlationIdInterceptor);
    }

    @Bean
    FleetClient demoFleetClient(
            JdkClientHttpRequestFactory requestFactory,
            CorrelationIdInterceptor correlationIdInterceptor,
            @Value("${FLEET_URL}") String fleetUrl
    ) {
        return createClient(FleetClient.class, fleetUrl, requestFactory, correlationIdInterceptor);
    }

    private static <T> T createClient(
            Class<T> clientType,
            String baseUrl,
            JdkClientHttpRequestFactory requestFactory,
            CorrelationIdInterceptor correlationIdInterceptor
    ) {
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(correlationIdInterceptor)
                .build();

        HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();

        return proxyFactory.createClient(clientType);
    }
}
