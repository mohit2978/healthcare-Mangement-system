package com.hungrycoders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-gateway")
public record ApiGatewayProperties(
        String authServiceBaseUrl,
        String authServiceUri,
        String doctorServiceBaseUrl,
        String doctorServiceUri,
        String patientServiceBaseUrl,
        String patientServiceUri,
        String appointmentsServiceBaseUrl,
        String appointmentsServiceUri) {}
