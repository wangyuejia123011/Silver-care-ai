package com.elderly.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工单实时推送 WebSocket —— 护工端小程序连接 /ws/order?caregiverId=xxx
 * 有新工单时服务端主动推送JSON，护工端弹窗提醒。
 *
 * 优化：增加心跳检测机制，服务端每30秒发送ping，
 * 客户端回应pong，连续90秒未收到pong则主动关闭连接，
 * 防止连接静默断开导致收不到新工单推送。
 */
@Component
public class OrderWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderWebSocketHandler.class);

    /** caregiverId -> WebSocket会话 */
    private static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    /** sessionId -> 最后活跃时间戳（收到pong或任何消息时更新） */
    private static final Map<String, Long> LAST_ACTIVE = new ConcurrentHashMap<>();

    /** 心跳间隔（秒） */
    private static final int HEARTBEAT_INTERVAL = 30;

    /** 连接超时阈值（秒），超过此时间未收到pong则关闭 */
    private static final int CONNECTION_TIMEOUT = 90;

    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    public void init() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        // 每30秒执行一次心跳检测
        heartbeatScheduler.scheduleAtFixedRate(this::heartbeatCheck,
                HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
        log.info("WebSocket心跳检测已启动，间隔{}秒，超时阈值{}秒", HEARTBEAT_INTERVAL, CONNECTION_TIMEOUT);
    }

    @PreDestroy
    public void destroy() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
            log.info("WebSocket心跳检测已停止");
        }
    }

    /**
     * 心跳检测：向所有在线连接发送ping，清理超时连接
     */
    private void heartbeatCheck() {
        long now = System.currentTimeMillis();
        String pingMsg = JSON.toJSONString(Map.of("type", "ping", "ts", now));

        SESSIONS.forEach((key, session) -> {
            if (!session.isOpen()) {
                SESSIONS.remove(key);
                LAST_ACTIVE.remove(session.getId());
                return;
            }
            // 检查是否超时
            Long lastActive = LAST_ACTIVE.get(session.getId());
            if (lastActive != null && (now - lastActive) > CONNECTION_TIMEOUT * 1000L) {
                log.warn("WebSocket连接超时，主动关闭: key={}, 最后活跃={}ms前",
                        key, (now - lastActive) / 1000);
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException e) {
                    log.debug("关闭超时连接失败: {}", e.getMessage());
                }
                SESSIONS.remove(key);
                LAST_ACTIVE.remove(session.getId());
                return;
            }
            // 发送ping
            send(session, pingMsg);
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String key = sessionKey(session);
        if (key != null) {
            SESSIONS.put(key, session);
            log.info("端侧已连接: key={}, 当前在线={}", key, SESSIONS.size());
        } else {
            SESSIONS.put("anon:" + session.getId().hashCode(), session);
        }
        LAST_ACTIVE.put(session.getId(), System.currentTimeMillis());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String key = sessionKey(session);
        if (key != null) {
            SESSIONS.remove(key);
        } else {
            SESSIONS.remove("anon:" + session.getId().hashCode());
        }
        LAST_ACTIVE.remove(session.getId());
        log.info("端侧断开: key={}, 剩余在线={}, 关闭原因={}", key, SESSIONS.size(), status);
    }

    /**
     * 处理客户端消息：主要处理pong心跳回应
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 更新最后活跃时间
        LAST_ACTIVE.put(session.getId(), System.currentTimeMillis());

        String payload = message.getPayload();
        try {
            JSONObject json = JSON.parseObject(payload);
            String type = json.getString("type");
            if ("pong".equals(type)) {
                log.debug("收到pong: sessionId={}", session.getId());
                return;
            }
            // 其他类型消息可在此扩展处理
            log.debug("收到客户端消息: type={}, sessionId={}", type, session.getId());
        } catch (Exception e) {
            // 非JSON消息，忽略
            log.debug("收到非JSON消息，忽略: sessionId={}", session.getId());
        }
    }

    /**
     * 推送工单给指定护工；caregiverId为null时广播给所有在线护工端。
     */
    public void pushOrder(Long caregiverId, Object order) {
        JSONObject payload = new JSONObject();
        payload.put("type", "new_order");
        payload.put("data", order);
        String text = JSON.toJSONString(payload);

        if (caregiverId != null) {
            WebSocketSession session = SESSIONS.get(caregiverKey(caregiverId));
            if (session != null && session.isOpen()) {
                send(session, text);
                return;
            }
        }
        SESSIONS.forEach((k, s) -> {
            if (k.startsWith("caregiver:") && s.isOpen()) send(s, text);
        });
    }

    /** 向绑定家属 / 护工推送健康预警 */
    public void pushHealthAlert(Object notify) {
        JSONObject payload = new JSONObject();
        payload.put("type", "health_alert");
        payload.put("data", notify);
        String text = JSON.toJSONString(payload);

        JSONObject n = notify instanceof JSONObject
                ? (JSONObject) notify
                : JSON.parseObject(JSON.toJSONString(notify));
        Long caregiverId = n.getLong("caregiverId");
        String openId = n.getString("receiverOpenId");

        boolean delivered = false;
        if (caregiverId != null) {
            WebSocketSession session = SESSIONS.get(caregiverKey(caregiverId));
            if (session != null && session.isOpen()) {
                send(session, text);
                delivered = true;
            }
        }
        if (openId != null && !openId.isBlank()) {
            WebSocketSession session = SESSIONS.get(familyKey(openId));
            if (session != null && session.isOpen()) {
                send(session, text);
                delivered = true;
            }
        }
        if (!delivered) {
            log.info("健康预警已落库，接收人当前不在线: caregiverId={}, openId={}", caregiverId, openId);
        }
    }

    private void send(WebSocketSession session, String text) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.warn("WebSocket推送失败: {}", e.getMessage());
        }
    }

    private Long extractCaregiverId(WebSocketSession session) {
        String v = queryParam(session, "caregiverId");
        if (v == null) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sessionKey(WebSocketSession session) {
        String role = queryParam(session, "role");
        String openId = queryParam(session, "openId");
        Long caregiverId = extractCaregiverId(session);
        if ("family".equals(role) && openId != null) {
            return familyKey(openId);
        }
        if (caregiverId != null) {
            return caregiverKey(caregiverId);
        }
        if (openId != null) {
            return familyKey(openId);
        }
        return null;
    }

    private String queryParam(WebSocketSession session, String name) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String kv : query.split("&")) {
            String[] pair = kv.split("=", 2);
            if (name.equals(pair[0]) && pair.length == 2) {
                return pair[1];
            }
        }
        return null;
    }

    private String caregiverKey(Long id) {
        return "caregiver:" + id;
    }

    private String familyKey(String openId) {
        return "family:" + openId;
    }
}
