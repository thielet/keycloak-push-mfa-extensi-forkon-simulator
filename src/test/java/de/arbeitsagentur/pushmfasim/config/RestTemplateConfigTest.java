package de.arbeitsagentur.pushmfasim.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {

    @Test
    void shouldConfigureProxyWhenHostAndPortAreSet() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn("proxy.example.com");
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(8080);

        RestTemplateConfig config = new RestTemplateConfig(proxyConfig);
        RestTemplate restTemplate = new RestTemplate();

        // when
        config.customize(restTemplate);

        // then
        ClientHttpRequestFactory requestFactory = restTemplate.getRequestFactory();
        assertThat(requestFactory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    void shouldNotConfigureProxyWhenHostIsNull() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn(null);
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(8080);

        RestTemplateConfig config = new RestTemplateConfig(proxyConfig);
        RestTemplate restTemplate = new RestTemplate();
        ClientHttpRequestFactory originalFactory = restTemplate.getRequestFactory();

        // when
        config.customize(restTemplate);

        // then
        assertThat(restTemplate.getRequestFactory()).isSameAs(originalFactory);
    }

    @Test
    void shouldNotConfigureProxyWhenPortIsMinusOne() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn("proxy.example.com");
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(-1);

        RestTemplateConfig config = new RestTemplateConfig(proxyConfig);
        RestTemplate restTemplate = new RestTemplate();
        ClientHttpRequestFactory originalFactory = restTemplate.getRequestFactory();

        // when
        config.customize(restTemplate);

        // then
        assertThat(restTemplate.getRequestFactory()).isSameAs(originalFactory);
    }
}
