package com.fc.v2.service.flog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;

import com.fc.v2.model.auto.TFlogYieldRule;

/**
 * 林业采伐线判定的<b>唯一口径</b>。规则线（强度，立方米/公顷）与建档底档（蓄积，立方米）
 * 两套阈值共用同一套判定规矩，避免同一规矩在多处各写各的、账凑不拢。
 *
 * <p>权威口径以 doc/schema/flog.sql 注释为准：
 * <ul>
 *   <li>分档：<b>等于上限算高一档</b>（input==th1 入 2 档，踩线升档）；</li>
 *   <li>生效窗口：启用之日含、<b>交棒之日(不含)</b>，即 [effStart, effEnd)；</li>
 *   <li>抢线顺位 priority：<b>数值越大越先开口</b>；</li>
 *   <li>规则可用：现行未删 + 在窗口内 + 至少一条阈值 + 重度上限不越强度域(100)。</li>
 * </ul>
 */
public final class FlogCaliber {

    /** 强度域上界（立方米/公顷） */
    public static final BigDecimal INTENSITY_MAX = new BigDecimal("100");

    private static final String TS_FMT = "yyyy-MM-dd HH:mm:ss";
    private static final String ZERO_DAY = "0000-00-00";

    private FlogCaliber() {
    }

    /**
     * 墙钟字符串（yyyy-MM-dd HH:mm:ss）。JDBC serverTimezone 与本机时区不对称，
     * 直接绑 java.util.Date 会整体偏移，使"恰好到期/恰好交棒"等边界错判，故统一走字符串。
     */
    public static String ts(Date d) {
        return d == null ? null : new SimpleDateFormat(TS_FMT).format(d);
    }

    /**
     * 三线上限分档（1..4）。规矩：等于上限算高一档，即仅当 input 严格小于某线上限才落在该档。
     * 阈值允许为 null（该档未维护则跳过，往下一档判）；input 为 null 返回 0。
     *
     * @param th1 轻度线上限
     * @param th2 中度线上限
     * @param th3 重度线上限
     */
    public static int grade(BigDecimal th1, BigDecimal th2, BigDecimal th3, BigDecimal input) {
        if (input == null) {
            return 0;
        }
        int level = 4;
        if (th3 != null && input.compareTo(scaled(th3)) < 0) {
            level = 3;
        }
        if (th2 != null && input.compareTo(scaled(th2)) < 0) {
            level = 2;
        }
        if (th1 != null && input.compareTo(scaled(th1)) < 0) {
            level = 1;
        }
        return level;
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 是否落在生效窗口 [start, end)：启用之日含、交棒之日不含。
     * 起止为 null 分别视为"早已启用"/"永不交棒"；0000-00-00 是无交棒日的占位，按无上限处理。
     */
    public static boolean inWindow(Date start, Date end, Date at) {
        if (at == null) {
            return false;
        }
        String now = ts(at);
        String s = ts(start);
        String e = ts(end);
        if (e != null && e.startsWith(ZERO_DAY)) {
            e = null;
        }
        boolean started = (s == null) || s.compareTo(now) <= 0;
        boolean notHandedOver = (e == null) || now.compareTo(e) < 0;
        return started && notHandedOver;
    }

    /**
     * 规则线在 at 时刻是否真正可用。列表、计数、按 id 旁路判定、按代号定位都必须走这一份，
     * 覆盖范围不得各判各的。
     */
    public static boolean ruleUsable(TFlogYieldRule r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getStatus() == null || r.getStatus().intValue() != 0) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
            return false;
        }
        if (!inWindow(r.getEffStart(), r.getEffEnd(), at)) {
            return false;
        }
        BigDecimal t1 = r.getTh1Max();
        BigDecimal t2 = r.getTh2Max();
        BigDecimal t3 = r.getTh3Max();
        if (t1 == null && t2 == null && t3 == null) {
            return false;
        }
        if (t3 != null && t3.compareTo(INTENSITY_MAX) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 抢线顺位排序：priority 数值大者在前（null 视作最小）；同顺位按 ruleCode 降序；
     * 再相同按 id 升序兜底。全程确定，权重相同也不会因行序/哈希而漂移。
     */
    public static Comparator<TFlogYieldRule> priorityOrdering() {
        return new Comparator<TFlogYieldRule>() {
            @Override
            public int compare(TFlogYieldRule a, TFlogYieldRule b) {
                int c = Integer.compare(pri(b), pri(a));
                if (c != 0) {
                    return c;
                }
                c = compareCodeDesc(a.getRuleCode(), b.getRuleCode());
                if (c != 0) {
                    return c;
                }
                return Long.compare(id(a), id(b));
            }
        };
    }

    /** 同一代号多个版本在 at 时刻的取舍：启用之日最新者开口；同启用日再比顺位、id。 */
    public static Comparator<TFlogYieldRule> versionOrdering() {
        return new Comparator<TFlogYieldRule>() {
            @Override
            public int compare(TFlogYieldRule a, TFlogYieldRule b) {
                int c = compareStartDesc(a.getEffStart(), b.getEffStart());
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(pri(b), pri(a));
                if (c != 0) {
                    return c;
                }
                return Long.compare(id(b), id(a));
            }
        };
    }

    private static int pri(TFlogYieldRule r) {
        return r.getPriority() == null ? 0 : r.getPriority().intValue();
    }

    private static long id(TFlogYieldRule r) {
        return r.getId() == null ? 0L : r.getId().longValue();
    }

    /** ruleCode 降序，null 视作最小排到最后。 */
    private static int compareCodeDesc(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }

    /** 启用日降序，null（无启用日=早已启用）视作最早排到最后。 */
    private static int compareStartDesc(Date a, Date b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return ts(b).compareTo(ts(a));
    }
}
