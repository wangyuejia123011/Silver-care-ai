package com.elderly.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文数字 → 阿拉伯数字 工具。
 * 方言/口语健康播报常出现：十六点五、一百二、三十六点五、一千六百等，
 * 统一转成 16.5 / 120 / 36.5 / 1600，便于落库、显示与前端"您说的是"回显。
 */
public class CnNumberUtil {

    // 中文数字串（可含小数"点"）：例如 一百六、十六点五、三十六点五
    private static final Pattern CN_NUM_SPAN = Pattern.compile(
            "[零一二两三四五六七八九十百千万]+(?:[点.][零一二两三四五六七八九十百千万0-9]+)?[多左右大概约几]*");

    /**
     * 将文本中所有中文数字（含小数、十百千万）转为阿拉伯数字。
     * 不影响其它文字；阿拉伯数字原样保留；日期单位（月/日/号/年/时/分）前的数字不误转。
     * 例：血压一百六八十 → 血压160/80；血糖十六点五 → 血糖16.5；体温三十六点五 → 体温36.5
     */
    public static String normalize(String text) {
        if (text == null) return null;
        Matcher m = CN_NUM_SPAN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String span = m.group();
            int end = m.end();
            // 日期单位前不转，避免"十一月""三十日""三号"被改坏
            if (end < text.length()) {
                char next = text.charAt(end);
                if (next == '月' || next == '日' || next == '号' || next == '年'
                        || next == '时' || next == '分') {
                    m.appendReplacement(sb, Matcher.quoteReplacement(span));
                    continue;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(toArabic(span)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 单个中文数字串 → 阿拉伯数字串；无法解析时原样返回 */
    public static String toArabic(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw.replaceAll("[多左右大概约几]", "").trim();
        if (s.isEmpty()) return raw;
        // 已是阿拉伯数字
        try {
            double d = Double.parseDouble(s);
            return formatNum(d);
        } catch (NumberFormatException ignore) {
            // 继续尝试中文
        }
        if (s.contains("点") || s.contains(".")) {
            String[] p = s.split("[点.]", 2);
            String intPart = p[0];
            // 口语补十：一百六→160、一百二→120（百后紧跟单个数字且为末尾、中间无十/零）
            if (intPart.matches("^[零一二两三四五六七八九十百千万]*百[零一二两三四五六七八九十]$")) {
                intPart = intPart.replaceFirst("百([零一二两三四五六七八九十])$", "百$1十");
            }
            double ip = cnToInt(intPart);
            double frac = 0;
            if (p.length > 1 && !p[1].isEmpty()) {
                StringBuilder fb = new StringBuilder();
                for (char c : p[1].toCharArray()) {
                    int d = cnDigit(c);
                    if (d >= 0) fb.append(d);
                }
                if (fb.length() > 0) {
                    frac = Integer.parseInt(fb.toString()) / Math.pow(10, fb.length());
                }
            }
            return formatNum(ip + frac);
        }
        // 整数：同样补十
        if (s.matches("^[零一二两三四五六七八九十百千万]*百[零一二两三四五六七八九十]$")) {
            s = s.replaceFirst("百([零一二两三四五六七八九十])$", "百$1十");
        }
        return formatNum(cnToInt(s));
    }

    private static String formatNum(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        // 去掉多余小数位
        return String.valueOf(Math.round(v * 1000.0) / 1000.0);
    }

    private static int cnDigit(char c) {
        switch (c) {
            case '零': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static int cnToInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignore) {
            // 中文
        }
        int total = 0, num = 0;
        boolean hasNum = false;
        for (char c : s.toCharArray()) {
            int d = cnDigit(c);
            if (d >= 0) {
                num = d;
                hasNum = true;
            } else if (c == '十') {
                total += (num == 0 && !hasNum) ? 10 : num * 10;
                num = 0;
                hasNum = false;
            } else if (c == '百') {
                total += (num == 0) ? 100 : num * 100;
                num = 0;
                hasNum = false;
            } else if (c == '千') {
                total += (num == 0) ? 1000 : num * 1000;
                num = 0;
                hasNum = false;
            } else if (c == '万') {
                total = (total + (num == 0 && !hasNum ? 0 : num)) * 10000;
                num = 0;
                hasNum = false;
            }
        }
        total += num;
        return total;
    }
}
