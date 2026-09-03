package com.fa26se040.icss.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Topic để broadcast thông báo tới các client Frontend
        config.enableSimpleBroker("/topic");
        // Prefix cho các request gửi từ client lên server
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint kết nối WebSocket hỗ trợ SockJS fallback
        registry.addEndpoint("/ws-security")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // Endpoint thuần WebSocket (chuẩn HTML5 WebSocket)
        registry.addEndpoint("/ws-security")
                .setAllowedOriginPatterns("*");
    }
}
