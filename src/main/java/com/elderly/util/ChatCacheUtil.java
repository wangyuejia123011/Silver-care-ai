package com.elderly.util;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 高频问答Redis缓存（24小时有效期，节省Token）。
 * Redis不可用时自动降级为直连大模型，不影响主流程。
 */
@Component
public class ChatCacheUtil {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheUtil.class);

    private static final String KEY_PREFIX = "qa:";

    // 指定bean名称 stringRedisTemplate，对应SpringBoot自动创建的StringRedisTemplate实例
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** 查询缓存命中的问题答案 */
    public String get(String question) {
        try {
            return stringRedisTemplate.opsForValue().get(KEY_PREFIX + md5(question));
        } catch (Exception e) {
            log.debug("Redis不可用，跳过缓存查询: {}", e.getMessage());
            return null;
        }
    }

    /** 缓存答案24小时 */
    public void put(String question, String answer) {
        try {
            stringRedisTemplate.opsForValue().set(KEY_PREFIX + md5(question), answer, Duration.ofHours(24));
        } catch (Exception e) {
            log.debug("Redis不可用，跳过缓存写入: {}", e.getMessage());
        }
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

