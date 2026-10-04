import java.time.LocalDateTime;

/**
 * ServiceTimeUtil 解析逻辑自测。
 * 不依赖 JUnit，直接 main 跑，输出每条用例的期望/实际。
 */
public class TestServiceTime {

    static int pass = 0;
    static int fail = 0;

    /** 周三 2026-10-07 */
    static LocalDateTime wed(int h, int m) {
        return LocalDateTime.of(2026, 10, 7, h, m, 0);
    }

    /** 周六 2026-10-10 */
    static LocalDateTime sat(int h, int m) {
        return LocalDateTime.of(2026, 10, 10, h, m, 0);
    }

    static void check(String desc, boolean expected, String serviceTime, LocalDateTime now) {
        boolean actual = com.elderly.util.ServiceTimeUtil.isOnDuty(serviceTime, now);
        boolean ok = expected == actual;
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.printf("%s %-46s 期望=%-5s 实际=%-5s  [%s]%n",
                ok ? "PASS" : "FAIL", desc, expected, actual, serviceTime);
    }

    public static void main(String[] args) {
        // ---- 基础单时段 ----
        check("周三 9点 在 8:00-10:00 内", true, "8:00-10:00", wed(9, 0));
        check("周三 7点 在 8:00-10:00 外", false, "8:00-10:00", wed(7, 0));
        check("周三 8:00 起始边界(含)", true, "8:00-10:00", wed(8, 0));
        check("周三 10:00 结束边界(含)", true, "8:00-10:00", wed(10, 0));
        check("周三 10:01 已过", false, "8:00-10:00", wed(10, 1));
        check("带前导零 08:00-20:00 周三18点", true, "08:00-20:00", wed(18, 0));
        check("全角冒号 ８：００-１０：００", true, "8：00-10：00", wed(9, 30));

        // ---- 全天类 ----
        check("全天在岗", true, "全天在岗", wed(3, 0));
        check("全天", true, "全天", wed(23, 59));
        check("24小时", true, "24小时", wed(2, 0));
        check("空字符串视为在岗", true, "", wed(2, 0));
        check("null 视为在岗", true, null, wed(2, 0));
        check("无法解析的乱码兜底为在岗", true, "看情况吧", wed(2, 0));

        // ---- 多时段 ----
        check("多时段命中第一个", true, "8:00-10:00,14:00-18:00", wed(9, 0));
        check("多时段命中第二个", true, "8:00-10:00,14:00-18:00", wed(15, 0));
        check("多时段都不命中", false, "8:00-10:00,14:00-18:00", wed(12, 0));

        // ---- 星期限定 ----
        check("周一至周五 周三在岗", true, "周一至周五 8:00-18:00", wed(9, 0));
        check("周一至周五 周六休息", false, "周一至周五 8:00-18:00", sat(9, 0));
        check("周六周日 周六在岗", true, "周六周日 全天", sat(9, 0));
        check("周六周日 周三休息", false, "周六周日 全天", wed(9, 0));
        check("周一至周五 但周三超时", false, "周一至周五 8:00-18:00", wed(19, 0));

        // ---- 跨夜班 ----
        check("跨夜 22:00-06:00 凌晨3点", true, "22:00-06:00", wed(3, 0));
        check("跨夜 22:00-06:00 晚23点", true, "22:00-06:00", wed(23, 0));
        check("跨夜 22:00-06:00 中午12点", false, "22:00-06:00", wed(12, 0));

        // ---- 单时刻（无区间）----
        check("只写时刻 8:00，9点在岗", true, "8:00", wed(9, 0));
        check("只写时刻 8:00，7点不在岗", false, "8:00", wed(7, 0));

        System.out.println();
        System.out.println("通过 " + pass + " 条，失败 " + fail + " 条");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
