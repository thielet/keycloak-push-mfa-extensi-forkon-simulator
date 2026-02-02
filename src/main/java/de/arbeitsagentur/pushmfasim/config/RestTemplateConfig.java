package de.arbeitsagentur.pushmfasim.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig implements RestTemplateCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(RestTemplateConfig.class);

    private final ProxyConfig proxyConfig;

    public RestTemplateConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Override
    public void customize(RestTemplate restTemplate) {
        if (proxyConfig.getHttpHost() != null && proxyConfig.getHttpPort() != -1) {
            HttpHost proxy = new HttpHost(proxyConfig.getHttpHost(), proxyConfig.getHttpPort());
            HttpClient httpClient = HttpClientBuilder.create()
                    .setRoutePlanner(new DefaultProxyRoutePlanner(proxy) {
                        @Override
                        public HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
                            logger.debug(
                                    "Using proxy with host: {} port: {} schema: {}",
                                    proxy.getHostName(),
                                    proxy.getPort(),
                                    proxy.getSchemeName());
                            return super.determineProxy(target, context);
                        }
                    })
                    .build();

            restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
        }
    }
}
