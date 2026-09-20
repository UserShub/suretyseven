package com.suretyseven.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A bounded HTTP client for calls to the external Applicant API. The
 * connect/read timeouts here are a hard backstop underneath Resilience4j's
 * TimeLimiter -- belt and suspenders, since a TimeLimiter alone cancels
 * waiting for the result but doesn't itself interrupt a blocking socket
 * read.
 *
 * Deliberately uses Spring's built-in SimpleClientHttpRequestFactory
 * (JDK HttpURLConnection under the hood) rather than pulling in Apache
 * HttpClient5: one fewer dependency, and its millisecond-based
 * setConnectTimeout/setReadTimeout API is unambiguous across Spring
 * versions. Fine for this service's needs (single GET call, no connection
 * pooling requirements); swap in Apache HttpClient5 or the JDK
 * java.net.http client if pooling/HTTP2 becomes important later.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${external.applicant-api.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${external.applicant-api.read-timeout-ms:4000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        return RestClient.builder().requestFactory(requestFactory);
    }
}
