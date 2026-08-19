package com.hungrycoders.filter;

import com.hungrycoders.config.ApiGatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ApiGateWayFilter implements GlobalFilter, Ordered {

    private final ApiGatewayProperties apiGatewayProperties;
    private final WebClient webClient;

    public ApiGateWayFilter(WebClient.Builder builder, ApiGatewayProperties apiGatewayProperties) {
        this.apiGatewayProperties = apiGatewayProperties;
        this.webClient =
                builder.baseUrl(apiGatewayProperties.authServiceBaseUrl()).build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getURI().getPath();

        // Pass requests bound for the auth-service directly without re-authenticating
        if (path.startsWith(apiGatewayProperties.authServiceUri())) {
            return chain.filter(exchange);
        }

        // Call the auth-service before calling any other microservice
        // Request URIs: (/api/v1/doctor/** /api/v1/patient/** /api/v1/appointments/**)
        return webClient
                .post()
                .uri(apiGatewayProperties.authServiceUri())
                .headers(httpHeaders -> {
                    httpHeaders.addAll(request.getHeaders());
                    httpHeaders.set("X-Original-Method", request.getMethod().name());
                    httpHeaders.set("X-Original-Path", path);
                })
                .exchangeToMono(response -> {
                    // If Auth service returns 201 ACCEPTED or 200 OK, proceed to the destination service
                    if (response.statusCode() == HttpStatus.ACCEPTED || response.statusCode() == HttpStatus.OK) {
                        return chain.filter(exchange);
                    }
                    // Otherwise, drop the connection and copy the error payload/status back to the client
                    exchange.getResponse().setStatusCode(response.statusCode());
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
