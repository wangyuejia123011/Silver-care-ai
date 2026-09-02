package com.elderly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 允许微信小程序前端跨域访问。
 *
 * 优化：支持通过配置文件 cors.allowed-origins 设置允许的来源，
 * 多个来源用逗号分隔。默认 "*" 兼容开发环境，
 * 生产环境应配置为具体域名白名单。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** 允许的跨域来源，多个用逗号分隔，默认 *（仅开发环境使用） */
    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        // 去除每个来源的首尾空格
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }

        if ("*".equals(allowedOrigins.trim())) {
            log.warn("CORS 配置为允许所有来源(*)，仅适用于开发环境，生产环境请配置 cors.allowed-origins 域名白名单");
            registry.addMapping("/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        } else {
            log.info("CORS 允许来源: {}", String.join(", ", origins));
            registry.addMapping("/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }
}
