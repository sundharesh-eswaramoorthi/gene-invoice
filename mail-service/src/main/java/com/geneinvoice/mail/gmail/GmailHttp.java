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

/** The HTTP client for calls to Google. */
final class GmailHttp {

    private GmailHttp() {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** A worker waits on a hung call, and its mailbox and queue slot with it, so not for long. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    /**
     * The longest string an answer may hold. A received message comes as one base64url string: Gmail
     * takes mail of up to 50 MB, about 67 million characters, and Jackson stops at 20 million by default.
     */
    static final int MAX_STRING_LENGTH = 80_000_000;

    /**
     * On the JDK client, which never repeats a POST by itself. HttpURLConnection silently resends one
     * whose connection drops, which for a send means the customer gets the email twice.
     */
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
