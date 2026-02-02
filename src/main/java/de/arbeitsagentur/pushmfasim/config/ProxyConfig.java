package de.arbeitsagentur.pushmfasim.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    @Value("${proxy.http.host:#{null}}")
    private String httpHost;

    @Value("${proxy.http.port:-1}")
    private int httpPort;
}
