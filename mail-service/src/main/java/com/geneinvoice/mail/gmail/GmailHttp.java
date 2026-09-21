package com.geneinvoice.mail.gmail;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

final class GmailHttp {

    private GmailHttp() {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_STRING_LENGTH = 80_000_000;

    static RestClient restClient() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(READ_TIMEOUT);
        ObjectMapper json = Jackson2ObjectMapperBuilder.json()
                .factory(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_STRING_LENGTH).build())
                        .build())
                .build();
        return RestClient.builder()
                .requestFactory(requests)
                .messageConverters(converters -> converters.replaceAll(converter ->
                        converter instanceof MappingJackson2HttpMessageConverter
                                ? new MappingJackson2HttpMessageConverter(json) : converter))
                .build();
    }
}
