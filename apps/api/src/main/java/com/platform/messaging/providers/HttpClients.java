package com.platform.messaging.providers;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

final class HttpClients {
    private HttpClients() {}

    /** Every provider call has explicit connect/read timeouts. */
    static RestClient.Builder withTimeouts(RestClient.Builder b, Duration connect, Duration read) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connect);
        f.setReadTimeout(read);
        return b.clone().requestFactory(f);
    }
}
