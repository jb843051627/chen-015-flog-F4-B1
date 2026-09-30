package com.fc.v2.common.flog;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;

import com.fc.v2.model.auto.TFlogYieldRule;

/**
 * flog 林木采伐模块的统一口径（两处以上共用，只此一份）：
 *
 * 1. 分档：三线（轻度/中度/重度线上限）判档，**等于上限算高一档**
 *    （档案 doc/schema/flog.sql 种子说明），故一律用 &lt; 比较；阈值缺省的线跳过。
 * 2. 生效窗口：启用之日含、交棒之日（不含）——起端闭、止端开。
 * 3. 择优顺位：抢线顺位 priority 数值越大越先开口；同顺位按代号 ruleCode 降序钉死，
 *    杜绝同单两跑结果不一。
 *
 * @author fuce
 * @date 2026-09-30
 */
public final class FlogRules {

    /** 墙钟字符串格式：规避 JDBC serverTimezone 与本机时区不对称导致的边界整体偏移 */
    private static final String TS_FMT = "yyyy-MM-dd HH:mm:ss";

    /** MySQL 零值时间，读到即视为无止期 */
    private static final String ZERO_TS_PREFIX = "0000-00-00";

    private FlogRules() {
    }

    /** 时段比较统一走墙钟字符串 */
    public static String ts(Date d) {
        return new SimpleDateFormat(TS_FMT).format(d);
    }

    /**
     * 三线上限判档。
     *
     * @param th1 轻度线上限（null 表示该线未维护，跳过）
     * @param th2 中度线上限
     * @param th3 重度线上限
     * @param input 拟采值（非 null）
     * @return 1..4；三条线全缺返回 0（调用方按无法判档处理）
     */
    public static int levelOf(BigDecimal th1, BigDecimal th2, BigDecimal th3, BigDecimal input) {
        if (th1 != null && input.compareTo(th1) < 0) {
            return 1;
        }
        if (th2 != null && input.compareTo(th2) < 0) {
            return 2;
        }
        if (th3 != null && input.compareTo(th3) < 0) {
            return 3;
        }
        if (th1 == null && th2 == null && th3 == null) {
            return 0;
        }
        return 4;
    }

    /**
     * at 时刻是否落在生效窗口 [effStart, effEnd) 内：起端含、止端不含。
     * 起期 null 视为自古有效；止期 null（或 MySQL 零值）视为长期有效。
     */
    public static boolean inWindow(Date effStart, Date effEnd, Date at) {
        String now = ts(at);
        String s = effStart == null ? null : ts(effStart);
        String e = effEnd == null ? null : ts(effEnd);
        if (e != null && e.startsWith(ZERO_TS_PREFIX)) {
            e = null;
        }
        boolean started = (s == null) || s.compareTo(now) <= 0;
        boolean notEnded = (e == null) || e.compareTo(now) > 0;
        return started && notEnded;
    }

    /** 界限值线是否可用于判档：至少维护一条线，且重度线上限不越界（强度值域 0..100） */
    public static boolean evaluable(TFlogYieldRule r) {
        if (r == null) {
            return false;
        }
        if (r.getTh1Max() == null && r.getTh2Max() == null && r.getTh3Max() == null) {
            return false;
        }
        if (r.getTh3Max() != null && r.getTh3Max().compareTo(new BigDecimal("100")) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 可用规则择优排序：priority 降序（大者先开口）；同顺位 ruleCode 降序钉死平局。
     * 顺位缺省按 0 处理；代号缺省排末尾。
     */
    public static Comparator<TFlogYieldRule> priorityOrder() {
        return new Comparator<TFlogYieldRule>() {
            @Override
            public int compare(TFlogYieldRule a, TFlogYieldRule b) {
                int pa = a.getPriority() == null ? 0 : a.getPriority();
                int pb = b.getPriority() == null ? 0 : b.getPriority();
                if (pa != pb) {
                    return Integer.compare(pb, pa);
                }
                String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
                String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
                int byCode = cb.compareTo(ca);
                if (byCode != 0) {
                    return byCode;
                }
                return Long.compare(b.getId() == null ? 0L : b.getId(),
                        a.getId() == null ? 0L : a.getId());
            }
        };
    }
}
