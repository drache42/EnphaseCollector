package com.hz.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;

/**
 * Created by David on 22-Oct-17.
 */
@Configuration
public class EnphaseRestClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EnphaseRestClientConfig.class);

    public static final String SYSTEM = "/home.json";
    public static final String INVENTORY = "/inventory.json?deleted=1";
    public static final String PRODUCTION = "/production.json?details=1";
    public static final String CONTROLLER = "/info.xml";
    public static final String WIFI_INFO = "/admin/lib/wireless_display.json?site_info=0";
	public static final String WAN_INFO = "/admin/lib/network_display.json";
	public static final String METER_STREAM = "/stream/meter";  // needs installer user and password
	public static final String DEVICE_METERS = "/ivp/meters";
	public static final String POWER_METERS = "/ivp/meters/readings";

    private final EnphaseCollectorProperties config;

	@Autowired
	public EnphaseRestClientConfig(EnphaseCollectorProperties config) {
		this.config = config;
	}

	@Bean
	public RestTemplate enphaseRestTemplate(RestTemplateBuilder builder) {

		LOG.info("Reading from insecure Envoy controller endpoint {}", config.getController().getUrl());

		return builder
				.rootUri(config.getController().getUrl())
				.setConnectTimeout(Duration.ofSeconds(5))
				.setReadTimeout(Duration.ofSeconds(30))
				.build();
	}

	/**
	 * Needed for /ivp/meters and /ivp/meters/readings
	 * @return customer converter to handle json as application octect stream
	 */
	@Bean
	public HttpMessageConverters customConverters() {
		MappingJackson2HttpMessageConverter octetStreamConverter = new MappingJackson2HttpMessageConverter();
		octetStreamConverter.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
		return new HttpMessageConverters(octetStreamConverter);
	}

}
