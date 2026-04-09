package com.agent.orchestrator.config;

import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    private final ExecutionWebSocketHandler webSocketHandler;
    
    public WebSocketConfig(ExecutionWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        System.out.println("🔧 Регистрация WebSocket endpoint: /ws/execution");
        registry.addHandler(webSocketHandler, "/ws/execution")
                .setAllowedOrigins("http://localhost:5173", "http://localhost:5174", "http://localhost:5175");
    }
}
