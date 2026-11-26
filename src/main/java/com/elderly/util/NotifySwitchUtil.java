package com.elderly.util;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 老人端「通知家属」总开关。
 *
 * 语义：前端「我的」页的开关，决定系统能不能给绑定的家属/护工发微信订阅消息。
 *   - 开启：健康异常 / SOS / 漏服药时，向绑定人推微信订阅消息；
 *   - 关闭：即使绑了家属护工，也一律不推微信消息（站内通知和 WebSocket 不受影响，
 *           因为那是家属主动打开小程序才看到的，不属于"微信推送骚扰"）。
 *
 * 存储：Redis key = notify:switch:{userId}，值为 "1"/"0"。
 * 默认：没有设置过的老人视为"开启"（历史行为兼容，避免老用户升级后收不到预警）。
 *
 * 降级：Redis 不可用时用进程内 Map 兜底，保证单机下开关仍然生效。
 */
@Component
public class NotifySwitchUtil {

    private static final Logger log = LoggerFactory.getLogger(NotifySwitchUtil.class);

    private static final String KEY_PREFIX = "notify:switch:";

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** Redis 不可用时的进程内兜底 */
    private final Map<Long, Boolean> localSwitch = new ConcurrentHashMap<>();

    /** 是否允许向该老人绑定的家属/护工推送微信消息 */
    public boolean isEnabled(Long userId) {
        if (userId == null) return true; // 没有 userId 时不拦截，保持原有行为
        String key = KEY_PREFIX + userId;
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            if (v == null) return true; // 没设置过 → 默认开启
            return !"0".equals(v);
        } catch (Exception e) {
            Boolean local = localSwitch.get(userId);
            return local == null ? true : local;
        }
    }

    /** 设置开关状态 */
    public void setEnabled(Long userId, boolean enabled) {
        if (userId == null) return;
        String key = KEY_PREFIX + userId;
        try {
            stringRedisTemplate.opsForValue().set(key, enabled ? "1" : "0");
        } catch (Exception e) {
            log.warn("Redis不可用，通知开关仅本地生效: {}", e.getMessage());
        }
        localSwitch.put(userId, enabled);
    }
}
