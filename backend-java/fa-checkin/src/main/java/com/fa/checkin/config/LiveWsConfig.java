package com.fa.checkin.config;

import com.fa.auth.security.TokenBlacklist;
import com.fa.checkin.app.LiveBroadcaster;
import com.fa.common.security.JwtUtil;
import com.fa.common.security.TokenPayload;
import io.jsonwebtoken.JwtException;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 大屏实时名单：`/api/v1/admin/sessions/{id}/live?token=<JWT>`。
 *
 * <p>WebSocket 握手带不了 Authorization 头（浏览器限制），所以 JWT 走 query
 * —— 这是契约里就写明的做法。代价是 token 会进访问日志，因此它只能用于
 * 这个只读通道，不能当作通用鉴权方式。
 */
@Configuration
@EnableWebSocket
public class LiveWsConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(LiveWsConfig.class);
    private static final String PATTERN = "/api/v1/admin/sessions/*/live";

    private final LiveBroadcaster broadcaster;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;

    public LiveWsConfig(LiveBroadcaster broadcaster, JwtUtil jwtUtil, TokenBlacklist blacklist) {
        this.broadcaster = broadcaster;
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new LiveHandler(), PATTERN).setAllowedOriginPatterns("*");
    }

    private class LiveHandler extends TextWebSocketHandler {

        private long sessionId(WebSocketSession s) {
            String path = s.getUri() == null ? "" : s.getUri().getPath();
            String[] parts = path.split("/");
            // .../sessions/{id}/live → parts[len-2]
            return Long.parseLong(parts[parts.length - 2]);
        }

        private boolean authorized(WebSocketSession s) {
            URI uri = s.getUri();
            if (uri == null) {
                return false;
            }
            String token = UriComponentsBuilder.fromUri(uri).build()
                    .getQueryParams().getFirst("token");
            if (token == null || token.isBlank()) {
                return false;
            }
            try {
                TokenPayload p = jwtUtil.toPayload(jwtUtil.parse(token));
                if (!"TEACHER".equals(p.role()) && !"ADMIN".equals(p.role())) {
                    return false;
                }
                return !blacklist.isRevoked(p.jti());
            } catch (JwtException | IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession s) throws Exception {
            if (!authorized(s)) {
                s.close(CloseStatus.POLICY_VIOLATION.withReason("unauthorized"));
                return;
            }
            long sid = sessionId(s);
            broadcaster.subscribe(sid, s);
            s.sendMessage(new TextMessage("{\"type\":\"EVT_CONNECTED\",\"sessionId\":" + sid + "}"));
            log.info("ws-connected sessionId={} peers={}", sid, broadcaster.subscriberCount(sid));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            try {
                broadcaster.unsubscribe(sessionId(s), s);
            } catch (RuntimeException ignored) {
                // URI 解析失败的连接本来就没订阅成功
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            // 只读通道：客户端发来的任何内容都忽略（不做 ping/pong 之外的协议）
        }
    }

    /** 供其他组件查询/使用（避免再注入一个 Map）。 */
    public Map<String, Object> info() {
        return Map.of("pattern", PATTERN);
    }
}
