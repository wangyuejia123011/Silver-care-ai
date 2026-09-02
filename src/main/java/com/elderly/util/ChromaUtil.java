package com.elderly.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma 向量库工具：当前接入两个 collection——
 *   elder_health_guide —— 慢病医疗指南（HealthAgent 使用）
 *   elder_fraud_case   —— 老年诈骗真实案例（FraudAgent 使用）
 * 任一 collection 不可用时，降级直连大模型，不影响主流程。
 */
@Component
public class ChromaUtil {

    @Value("${chroma.url}")
    private String chromaUrl;

    @Value("${chroma.collection:elder_health_guide}")
    private String healthColl;

    @Value("${chroma.fraud.collection:elder_fraud_case}")
    private String fraudColl;

    /**
     * 检索 Top3 慢病医疗指南片段
     */
    @SuppressWarnings("unchecked")
    public List<String> searchMedicalDoc(String query) throws Exception {
        return searchTop(query, healthColl, 3);
    }

    /**
     * 检索 Top3 老年诈骗案例片段 —— 用于 FraudAgent RAG 比对
     */
    public List<String> searchFraudCase(String query) throws Exception {
        return searchTop(query, fraudColl, 3);
    }

    @SuppressWarnings("unchecked")
    private List<String> searchTop(String query, String coll, int n) throws Exception {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(chromaUrl + "/api/v1/collections/" + coll + "/query");
            Map<String, Object> param = new HashMap<>();
            param.put("query_texts", List.of(query));
            param.put("n_results", n);
            String json = JSON.toJSONString(param);
            post.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
            post.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int code = response.getCode();
                String resp = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                if (code != 200) {
                    throw new RuntimeException("chroma HTTP " + code + ": " + resp);
                }
                JSONObject result = JSON.parseObject(resp);
                Map<String, List<List<String>>> documents = result.to(Map.class);
                List<List<String>> docs = documents.get("documents");
                if (docs == null || docs.isEmpty()) {
                    return List.of();
                }
                return docs.get(0);
            }
        }
    }
}
