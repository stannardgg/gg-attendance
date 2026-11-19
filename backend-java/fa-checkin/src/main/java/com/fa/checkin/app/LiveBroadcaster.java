package com.fa.checkin.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 大屏实时推送（EVT_CHECKIN_SUCCESS / EVT_SESSION_CLOSED）。
 *
 * <p>为什么用最朴素的「sessionId → 连接集合」而不是 STOMP：
 * 只有一种客户端（大屏）、只有两种事件、没有订阅语义需要协商。
 * 引入 STOMP 要多一层 broker 概念，换不来任何实际能力。
 *
 * <p>推送失败<b>不影响签到</b>：广播在事务提交之后调用，异常吞掉只记日志。
 * 大屏掉线不能让学生签到失败。
 */
@Component
public class LiveBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LiveBroadcaster.class);

    private final Map<Long, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public LiveBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(long sessionId, WebSocketSession session) {
        subscribers.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(long sessionId, WebSocketSession session) {
        Set<WebSocketSession> set = subscribers.get(sessionId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                subscribers.remove(sessionId);
            }
        }
    }

    public int subscriberCount(long sessionId) {
        Set<WebSocketSession> set = subscribers.get(sessionId);
        return set == null ? 0 : set.size();
    }

    /** 广播一个事件。type 如 EVT_CHECKIN_SUCCESS，payload 为事件体。 */
    public void broadcast(long sessionId, String type, Object payload) {
        Set<WebSocketSession> set = subscribers.get(sessionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String text;
        try {
            text = mapper.writeValueAsString(Map.of(
                    "type", type,
                    "sessionId", sessionId,
                    "serverTime", java.time.OffsetDateTime.now().toString(),
                    "payload", payload));
        } catch (Exception e) {
            log.error("ws-serialize-failed sessionId={}", sessionId, e);
            return;
        }
        for (WebSocketSession s : set) {
            if (!s.isOpen()) {
                set.remove(s);
                continue;
            }
            try {
                // 同一 session 的并发发送会抛 IllegalStateException，故加锁
                synchronized (s) {
                    s.sendMessage(new TextMessage(text));
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("ws-send-failed sessionId={} reason={}", sessionId, e.getMessage());
                set.remove(s);
            }
        }
    }
}
