package com.hungrycoders.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(value = {ApiGatewayProperties.class})
public class ApiGatewayConfig {

    private final ApiGatewayProperties apiGatewayProperties;

    public ApiGatewayConfig(ApiGatewayProperties apiGatewayProperties) {
        this.apiGatewayProperties = apiGatewayProperties;
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                // Route configuration for doctor-service
                .route("doctor-service", r -> r.path(apiGatewayProperties.doctorServiceUri() + "/**")
                        .filters(f -> f.circuitBreaker(
                                c -> c.setName("doctorCircuitBreaker").setFallbackUri("forward:/fallback/doctor")))
                        // Correct base URL for doctor-service
                        .uri(apiGatewayProperties.doctorServiceBaseUrl()))
                // Route configuration for patient-service
                .route("patient-service", r -> r.path(apiGatewayProperties.patientServiceUri() + "/**")
                        .filters(f -> f.circuitBreaker(
                                c -> c.setName("patientCircuitBreaker").setFallbackUri("forward:/fallback/patient")))
                        // Correct base URL for patient-service
                        .uri(apiGatewayProperties.patientServiceBaseUrl()))
                // Route configuration for appointment-service
                .route("appointment-service", r -> r.path(apiGatewayProperties.appointmentsServiceUri() + "/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("appointmentServiceCircuitBreaker")
                                .setFallbackUri("forward:/fallback/appointment")))
                        // Correct base URL for appointment-service
                        .uri(apiGatewayProperties.appointmentsServiceBaseUrl()))
                // Route configuration for auth-service
                .route("auth-service", r -> r.path(apiGatewayProperties.authServiceUri() + "/**")
                        .filters(f -> f.circuitBreaker(
                                c -> c.setName("authCircuitBreaker").setFallbackUri("forward:/fallback/auth")))
                        // Correct base URL for auth-service
                        .uri(apiGatewayProperties.authServiceBaseUrl()))
                .build();
    }
}
