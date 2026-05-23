package com.mta.faultinjection.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class TargetKeyTest {

    @Test
    void stripsQueryStringFromPath() {
        TargetKey k = TargetKey.fromOutbound(HttpMethod.GET, URI.create("https://api.example.com/orders/42?nonce=abc"));
        assertThat(k.host()).isEqualTo("api.example.com");
        assertThat(k.method()).isEqualTo("GET");
        assertThat(k.urlPath()).isEqualTo("/orders/42");
    }

    @Test
    void equalityIgnoresQuery() {
        TargetKey a = TargetKey.fromOutbound(HttpMethod.GET, URI.create("https://h/p?x=1"));
        TargetKey b = TargetKey.fromOutbound(HttpMethod.GET, URI.create("https://h/p?x=2"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void relativeUriYieldsEmptyHost() {
        TargetKey k = TargetKey.fromOutbound(HttpMethod.POST, URI.create("/relative"));
        assertThat(k.host()).isEmpty();
    }

    @Test
    void hostMethodViewDropsPath() {
        TargetKey k = TargetKey.fromOutbound(HttpMethod.GET, URI.create("https://h/p"));
        HostMethodKey hm = k.hostMethodKey();
        assertThat(hm.host()).isEqualTo("h");
        assertThat(hm.method()).isEqualTo("GET");
    }
}
