package com.hz.configuration;

import com.hz.models.envoy.xml.EnvoyInfo;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.apache.http.auth.AuthScope.ANY_SCHEME;

/**
 * Created by David on 22-Oct-17.
 */
@Configuration
public class EnphaseSecureRestClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EnphaseSecureRestClientConfig.class);

    // Needs Digest authentication
    public static final String INVERTERS = "/api/v1/production/inverters";
	private static final String REALM = "enphaseenergy.com";

    private final EnphaseCollectorProperties config;
    private final EnvoyInfo envoyInfo;

	@Autowired
	public EnphaseSecureRestClientConfig(EnphaseCollectorProperties config, EnvoyInfo envoyInfo) {
		this.config = config;
		this.envoyInfo = envoyInfo;
	}

	private String getControllerPassword() {
		if (config.getController().getPassword() == null || config.getController().getPassword().isEmpty()) {
			return envoyInfo.getSerialNumber().substring(envoyInfo.getSerialNumber().length()-6);
		}

		return config.getController().getPassword();
	}

	private CredentialsProvider provider() {
		CredentialsProvider provider = new BasicCredentialsProvider();
		UsernamePasswordCredentials credentials =
				new UsernamePasswordCredentials(config.getController().getUser(), getControllerPassword());
		provider.setCredentials(new AuthScope(config.getController().getHost(), 80, REALM, ANY_SCHEME), credentials);
		return provider;
	}

    @Bean
    public RestTemplate enphaseSecureRestTemplate(RestTemplateBuilder builder) {

	    LOG.info("Reading from protected Envoy controller endpoint {}", config.getController().getUrl());

	    HttpClient httpClient = HttpClients
			    .custom()
			    .setDefaultCredentialsProvider(provider())
			    .useSystemProperties()
			    .build();

	    return builder
			    .rootUri(config.getController().getUrl())
			    .setConnectTimeout(Duration.ofSeconds(5))
			    .setReadTimeout(Duration.ofSeconds(30))
			    .requestFactory(() -> new HttpComponentsClientHttpRequestFactory(httpClient))
			    .build();
    }

}
