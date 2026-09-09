package com.elderly.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PromptUtil {
    // 替换@Resource注解为@Autowired，彻底避开包冲突
    @Autowired
    private ResourceLoader resourceLoader;

    // 读取提示词并替换{key}占位符
    public String getPrompt(String fileName, Map<String, String> params) throws Exception {
        // 使用 InputStream 读取，兼容 jar 内资源（云托管部署时为 jar:file:/app/app.jar!）
        // 注意：fileResource.getFile() 在 jar 内会抛 FileNotFoundException，必须用流
        Resource fileResource = resourceLoader.getResource("classpath:prompts/" + fileName);
        String content;
        try (InputStream in = fileResource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return content;
    }
}
