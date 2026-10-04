package ua.carrental.booking.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ua.carrental.booking.web.CorrelationIdInterceptor;

@Configuration(proxyBeanMethods = false)
public class CatalogClientConfiguration {
    @Bean(destroyMethod = "close")
    HttpClient catalogHttpClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Bean
    CatalogClient catalogClient(@Value("${FLEET_URL:http://localhost:8082}") String url,
            HttpClient http, CorrelationIdInterceptor interceptor) {
        return create(CatalogClient.class, url, http, interceptor);
    }

    @Bean
    CustomerClient customerClient(@Value("${CUSTOMER_URL:http://localhost:8081}") String url,
            HttpClient http, CorrelationIdInterceptor interceptor) {
        return create(CustomerClient.class, url, http, interceptor);
    }

    private <T> T create(Class<T> type, String url, HttpClient http,
            CorrelationIdInterceptor interceptor) {
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(3));
        RestClient rest = RestClient.builder().baseUrl(url).requestFactory(requests)
                .requestInterceptor(interceptor).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build().createClient(type);
    }
}
