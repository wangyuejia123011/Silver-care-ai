package com.elderly.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PromptUtil {
    // 替换@Resource注解为@Autowired，彻底避开包冲突
    @Autowired
    private ResourceLoader resourceLoader;

    // 读取提示词并替换{key}占位符
    public String getPrompt(String fileName, Map<String, String> params) throws Exception {
        // 修改变量名，不和Resource类重名
        Resource fileResource = resourceLoader.getResource("classpath:prompts/" + fileName);
        String content = Files.readString(fileResource.getFile().toPath(), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return content;
    }
}

