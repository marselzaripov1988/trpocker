package com.truholdem.config;

import com.truholdem.security.WebSocketAuthInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration with security.
 *
 * Security features:
 * - JWT authentication via STOMP headers on CONNECT
 * - Restricted allowed origins (configured via properties)
 * - User destination prefix for private messages
 */
@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "app.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    public WebSocketConfig(AppProperties appProperties, WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.appProperties = appProperties;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Get allowed origins from configuration, with secure defaults
        String[] allowedOrigins = getAllowedOrigins();

        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();

        // Also register without SockJS for native WebSocket clients
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Add JWT authentication interceptor for WebSocket messages
        registration.interceptors(webSocketAuthInterceptor);
    }

    /**
     * Native (Capacitor / Tauri) shells perform the SockJS handshake from these synthetic origins rather than the
     * backend host. They are app-controlled schemes a browser cannot forge, so they are always allowed in addition
     * to the configured web origins.
     */
    private static final String[] NATIVE_ORIGINS = {
            "http://localhost", "https://localhost",
            "capacitor://localhost", "ionic://localhost",
            "tauri://localhost", "https://tauri.localhost"
    };

    /**
     * Gets allowed origins from configuration (web origins) plus the always-allowed native-shell origins.
     * Falls back to localhost origins if the web list is not configured.
     */
    private String[] getAllowedOrigins() {
        var origins = appProperties.getWebsocket().getAllowedOrigins();

        java.util.LinkedHashSet<String> allowed = new java.util.LinkedHashSet<>();
        if (origins == null || origins.isEmpty()) {
            // Secure defaults - only localhost for development
            allowed.add("http://localhost:4200");
            allowed.add("http://localhost:3000");
        } else {
            // Filter out wildcard "*" for security
            origins.stream().filter(origin -> !"*".equals(origin)).forEach(allowed::add);
        }
        allowed.addAll(java.util.Arrays.asList(NATIVE_ORIGINS));

        return allowed.toArray(new String[0]);
    }
}
