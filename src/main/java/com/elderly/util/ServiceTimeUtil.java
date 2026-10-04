package com.elderly.util;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 护工「可服务时间」解析与在岗判定。
 *
 * <p>service_time 是护工自填的自由文本（caregiver.service_time 列，VARCHAR(100)），
 * 实际可能写成：
 * <ul>
 *   <li>{@code 8:00-10:00} / {@code 08:00-20:00} —— 单日时段</li>
 *   <li>{@code 8:00-10:00,14:00-18:00} —— 逗号分隔的多时段</li>
 *   <li>{@code 全天在岗} / {@code 全天} / {@code 24小时} —— 视为全天候在岗</li>
 *   <li>{@code 周一至周五 8:00-18:00} / {@code 周六周日 全天} —— 带星期限定</li>
 * </ul>
 * 所以这里做**容错解析**：解析不出来时按"全天候在岗"处理，
 * 宁可多派单也不要让护工莫名其妙接不到单。
 *
 * <p>该类是纯函数、无状态，供「详情页状态文案」与「调度硬过滤」共用，
 * 保证两处口径完全一致（否则会出现详情页显示停止接单、调度却派给他的矛盾）。
 */
public final class ServiceTimeUtil {

    /** 全天在岗的关键词 */
    private static final String[] ALWAYS_ON_DUTY = {"全天", "24小时", "不限", "随时", "全天候"};

    /** 形如 8:00 / 08:00 / 8:30 的时间 */
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:：](\\d{2})");

    /** 形如 8:00-10:00 的时段 */
    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,2})[:：](\\d{2})\\s*[-~－—至]\\s*(\\d{1,2})[:：](\\d{2})");

    private ServiceTimeUtil() {
    }

    /**
     * 判断护工在指定时刻是否处于接单状态。
     *
     * @param serviceTime 可服务时间自由文本
     * @param now         判定时刻
     * @return true=在可服务时间内（实时接单中）
     */
    public static boolean isOnDuty(String serviceTime, LocalDateTime now) {
        // 未填写 / 空白 → 视为全天候在岗
        if (serviceTime == null || serviceTime.isBlank()) {
            return true;
        }
        String text = serviceTime.trim().toLowerCase(Locale.ROOT);

        // 星期限定必须最先判断。
        // 否则「周六周日 全天」会因为命中"全天"关键词直接 return true，
        // 导致周三也被判为在岗（"全天"只修饰当天，时段是次要的）。
        if (!matchWeekday(text, now.getDayOfWeek())) {
            return false;
        }

        // 明确写了全天关键词 → 在岗
        for (String kw : ALWAYS_ON_DUTY) {
            if (text.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        // 解析出所有时段，任一命中即在岗
        Matcher m = RANGE.matcher(text);
        boolean hasRange = false;
        while (m.find()) {
            hasRange = true;
            LocalTime start = toTime(m.group(1), m.group(2));
            LocalTime end = toTime(m.group(3), m.group(4));
            if (start == null || end == null) {
                continue;
            }
            if (within(now.toLocalTime(), start, end)) {
                return true;
            }
        }
        if (hasRange) {
            return false;
        }

        // 只有一个时刻（没写区间，如「8:00」）：视为"该时刻起一直可接单"，
        // 早于它则不在岗；这种写法语义模糊，按宽松处理更安全。
        Matcher t = TIME.matcher(text);
        if (t.find()) {
            LocalTime only = toTime(t.group(1), t.group(2));
            if (only != null) {
                return !now.toLocalTime().isBefore(only);
            }
        }

        // 完全解析不出来 → 兜底为在岗，避免护工莫名接不到单
        return true;
    }

    /** 便捷重载：用当前时间判定 */
    public static boolean isOnDuty(String serviceTime) {
        return isOnDuty(serviceTime, LocalDateTime.now());
    }

    /**
     * 星期限定匹配。
     * 未出现星期字样 → 不限定（true）。
     * 出现「周一至周五」这类区间 → 命中区间为 true。
     * 出现「周六周日」这类枚举 → 枚举命中为 true。
     * 「周一至周五」同时命中 周六/周日 的写法视为矛盾，按宽松返回 true。
     */
    private static boolean matchWeekday(String text, DayOfWeek dow) {
        if (!text.contains("周") && !text.contains("星期")) {
            return true;
        }
        // 统一的中文星期表：周一=1 ... 周日=7（与 java.time.DayOfWeek 数值一致）
        // 不能用 getDisplayName 拼"周"+"周一"（会得到"周周一"），
        // 也不能对 "周一".charAt(0) 取值（拿到的是"周"，永远匹配不上）。
        int cur = dow.getValue();

        // 「周一至周五」「周一-周五」「周一到周五」这类区间
        Matcher range = Pattern.compile("周([一二三四五六日天1-7])\\s*(?:至|到|~|-)\\s*周?([一二三四五六日天1-7])")
                .matcher(text);
        boolean sawRange = false;
        while (range.find()) {
            sawRange = true;
            int from = cnToIndex(range.group(1));
            int to = cnToIndex(range.group(2));
            if (from < 0 || to < 0) {
                continue;
            }
            if (cur >= Math.min(from, to) && cur <= Math.max(from, to)) {
                return true;
            }
        }
        if (sawRange) {
            return false;
        }

        // 单个枚举：「周六周日」「周一、周三」「星期日」
        boolean sawSingle = false;
        for (DayOfWeek d : DayOfWeek.values()) {
            String cn = cnWeekday(d);
            if (text.contains("周" + cn) || text.contains("星期" + cn)) {
                sawSingle = true;
                if (d == dow) {
                    return true;
                }
            }
        }
        // 出现了星期字样但没匹配上 → 今天不在其服务日
        return !sawSingle;
    }

    /** DayOfWeek → 中文单字（周一=一 ... 周日=日） */
    private static String cnWeekday(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    /** 中文星期单字或数字 → 1..7（周一=1，周日=7）；无法识别返回 -1 */
    private static int cnToIndex(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        return switch (s.charAt(0)) {
            case '一', '1' -> 1;
            case '二', '2' -> 2;
            case '三', '3' -> 3;
            case '四', '4' -> 4;
            case '五', '5' -> 5;
            case '六', '6' -> 6;
            case '日', '天', '7' -> 7;
            default -> -1;
        };
    }

    /**
     * 判断时刻是否落在 [start, end] 内。
     * 结束时间早于开始时间（如 22:00-06:00 的跨夜班）视为跨天，包含两端。
     */
    private static boolean within(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            // 0:00-0:00 当作全天
            return true;
        }
        if (end.isBefore(start)) {
            // 跨夜
            return !now.isBefore(start) || !now.isAfter(end);
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private static LocalTime toTime(String h, String m) {
        try {
            int hour = Integer.parseInt(h);
            int minute = Integer.parseInt(m);
            if (hour < 0 || hour > 24 || minute < 0 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour % 24, minute);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
