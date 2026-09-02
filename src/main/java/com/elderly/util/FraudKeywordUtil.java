package com.elderly.util;

import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 反诈第一层防火墙：本地关键词库，命中即拦截，响应时间<10ms。
 */
@Component
public class FraudKeywordUtil {

    private final List<String> riskWords = List.of(
            // 医疗保健品诈骗
            "根治", "根治糖尿病", "根治高血压", "根治癌症", "彻底治愈",
            "神药", "特效", "特效药", "祖传秘方", "秘方", "偏方治大病",
            "包治百病", "包治", "永不复发", "药到病除", "一盒见效",
            "保健品能治病", "保健品治病", "代替药物", "停药", "纯天然无副作用",
            "免费领", "扫码进群", "进群领", "限时抢购", "限量抢购",
            "专家坐诊", "神医", "老中医秘制",
            // 投资理财诈骗
            "百分百赚钱", "百分百回报", "稳赚不赔", "高额回报", "年化", "保本高收益",
            "低风险高收益", "内部消息", "内幕消息", "原始股", "虚拟货币", "区块链投资",
            "刷单返利", "做任务返利", "垫付", "充值返现",
            // 冒充/转账诈骗
            "转账汇款", "安全账户", "涉嫌洗钱", "配合调查", "公安局", "检察院", "法院",
            "中奖了", "领奖", "保证金", "解冻费", "手续费",
            // 养老项目诈骗
            "以房养老", "养老项目", "投资养老", "免费旅游", "免费体检"
    );

    /** 返回命中的风险关键词列表（空列表表示未命中） */
    public List<String> hitWords(String text) {
        if (text == null) return List.of();
        return riskWords.stream().filter(text::contains).toList();
    }

    /** 是否命中风险词 */
    public boolean hitRiskWord(String text) {
        return !hitWords(text).isEmpty();
    }
}
