package com.elderly.controller;

import com.elderly.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口
 */
@RestController
@RequestMapping("/api")
public class TestController {

    @GetMapping("/hello")
    public R<Map<String, Object>> hello() {
        Map<String, Object> res = new HashMap<>();
        res.put("msg", "银龄智护服务运行正常！");
        res.put("time", java.time.LocalDateTime.now().toString());
        return R.success(res);
    }
}
