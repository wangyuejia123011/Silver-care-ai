package com.elderly.util;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高危健康工单冷却器：同一老人、同一组高危指标在冷却窗口内只自动派单一次。
 *
 * 背景：线上曾在30秒内连生成3个内容几乎相同的工单（#127/#128/#129），
 * 原因是 HealthAgent 每次识别到高危都无条件 dispatch。这里用"用户+指标指纹"
 * 做幂等键，Redis 原子 setIfAbsent 保证并发下也只有一个请求能拿到派单资格。
 *
 * 降级策略：Redis 不可用时回退到进程内 ConcurrentHashMap（单机有效，
 * 多实例部署时防重复能力下降，但不会阻断派单主流程）。
 *
 * SOS 手动呼救不受此冷却限制——由调用方决定是否调用 tryAcquire。
 */
@Component
public class OrderCooldownUtil {

    private static final Logger log = LoggerFactory.getLogger(OrderCooldownUtil.class);

    /** 冷却窗口：2小时 */
    private static final Duration COOLDOWN = Duration.ofHours(2);
    private static final String KEY_PREFIX = "order:cooldown:";

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** Redis 不可用时的进程内兜底（key -> 过期时间戳毫秒） */
    private final Map<String, Long> localCooldown = new ConcurrentHashMap<>();

    /**
     * 尝试获取派单资格。
     *
     * @param userId 老人ID
     * @param fingerprint 指标指纹（同一个人、同一组指标视为同一事件）
     * @return true=允许派单并已占用冷却位；false=冷却中，应跳过自动派单
     */
    public boolean tryAcquire(Long userId, String fingerprint) {
        String key = KEY_PREFIX + (userId == null ? "0" : userId) + ":" + md5(fingerprint == null ? "" : fingerprint);
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", COOLDOWN);
            if (Boolean.TRUE.equals(ok)) {
                log.info("高危工单冷却位已占用, key={}", key);
                return true;
            }
            log.info("高危工单冷却中，跳过自动派单, key={}", key);
            return false;
        } catch (Exception e) {
            log.warn("Redis不可用，改用本地冷却兜底: {}", e.getMessage());
            return tryAcquireLocal(key);
        }
    }

    private boolean tryAcquireLocal(String key) {
        return tryAcquireLocal(key, COOLDOWN);
    }

    private boolean tryAcquireLocal(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Long expireAt = localCooldown.get(key);
        if (expireAt != null && expireAt > now) {
            return false;
        }
        // putIfAbsent 并发安全：只有一个线程能写入
        Long previous = localCooldown.putIfAbsent(key, now + ttl.toMillis());
        if (previous == null || previous <= now) {
            if (previous != null) {
                localCooldown.put(key, now + ttl.toMillis());
            }
            // 简单清理过期项，避免长期堆积
            if (localCooldown.size() > 512) {
                localCooldown.entrySet().removeIf(en -> en.getValue() <= now);
            }
            return true;
        }
        return false;
    }

    /** 主动释放冷却位（例如人工确认后决定重新派单时使用） */
    public void release(Long userId, String fingerprint) {
        String key = KEY_PREFIX + (userId == null ? "0" : userId) + ":" + md5(fingerprint == null ? "" : fingerprint);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("Redis不可用，跳过冷却位释放: {}", e.getMessage());
        }
        localCooldown.remove(key);
    }

    /**
     * 通用幂等键占用：任意场景用自定义 key + TTL 保证"只执行一次"。
     * 与 {@link #tryAcquire(Long, String)} 的区别是不做指纹哈希、不绑定用户，调用方自己构造 key。
     *
     * @return true=首次占用（本次应执行）；false=TTL 内已占用过（本次应跳过）
     */
    public boolean tryAcquireKey(String rawKey, Duration ttl) {
        if (rawKey == null || rawKey.isBlank()) return true;
        Duration window = (ttl == null || ttl.isZero() || ttl.isNegative()) ? COOLDOWN : ttl;
        String key = KEY_PREFIX + "generic:" + rawKey;
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", window);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("Redis不可用，改用本地幂等兜底: {}", e.getMessage());
            return tryAcquireLocal(key, window);
        }
    }

    /**
     * 构造指标指纹：只把"触发高危的关键指标"纳入，避免无关数值变化导致重复派单。
     * 例：血压180 + 体温38 → "s180|t38"。舒张压缺失不写 null，写 "-"。
     */
    public static String buildFingerprint(Integer systolic, Integer diastolic, Integer heartRate,
                                          Double bloodSugar, Double temperature) {
        return "s" + (systolic == null ? "-" : systolic)
                + "|d" + (diastolic == null ? "-" : diastolic)
                + "|h" + (heartRate == null ? "-" : heartRate)
                + "|g" + (bloodSugar == null ? "-" : bloodSugar)
                + "|t" + (temperature == null ? "-" : temperature);
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
