package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;

/**
 * 可采强度界限值线：边界判档 / 择优顺位 / 换版端点 / 统计口径复现。
 * 档案口径（doc/schema/flog.sql 种子说明）：等于上限算高一档；
 * eff_end 交棒之日（不含）；priority 数值越大越先开口。
 */
@ExtendWith(MockitoExtension.class)
class TFlogYieldRuleServiceImplTest {

    @Mock
    private TFlogYieldRuleMapper mapper;

    @InjectMocks
    private TFlogYieldRuleServiceImpl service;

    private static Date t(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TFlogYieldRule rule(long id, String code, Integer priority,
                                String th1, String th2, String th3,
                                String start, String end) {
        TFlogYieldRule r = new TFlogYieldRule();
        r.setId(id);
        r.setRuleCode(code);
        r.setRuleName(code);
        r.setPriority(priority);
        r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        r.setEffStart(start == null ? null : t(start));
        r.setEffEnd(end == null ? null : t(end));
        r.setStatus(0);
        r.setDelFlag(0);
        return r;
    }

    private Date at;

    @BeforeEach
    void setUp() {
        at = t("2026-09-15 10:00:00");
    }

    // ---- 谜面1：踩着线的该上没上（档案：等于上限算高一档）----

    @Test
    void evaluate_boundaryEqualsUpper_goesHigherTier() {
        when(mapper.selectList(any())).thenReturn(listOf(
                rule(1L, "R1", 1, "40", "80", "100", "2026-01-01 00:00:00", null)));
        assertEquals(1, service.evaluate("R1", new BigDecimal("39"), at), "39 < 40 → 轻度 1 档");
        assertEquals(2, service.evaluate("R1", new BigDecimal("40"), at), "40 恰好踩一线 → 该上 2 档");
        assertEquals(2, service.evaluate("R1", new BigDecimal("79"), at));
        assertEquals(3, service.evaluate("R1", new BigDecimal("80"), at), "80 踩二线 → 3 档");
        assertEquals(3, service.evaluate("R1", new BigDecimal("99"), at));
        assertEquals(4, service.evaluate("R1", new BigDecimal("100"), at), "100 踩三线 → 4 档");
    }

    @Test
    void evaluateTop_boundaryEqualsUpper_goesHigherTier() {
        when(mapper.selectList(any())).thenReturn(listOf(
                rule(1L, "R1", 1, "40", "80", "100", "2026-01-01 00:00:00", null)));
        assertEquals(2, service.evaluateTop(new BigDecimal("40"), at));
    }

    // ---- 谜面3：择优先后同档案里写的优先级掉了个个儿（大优先）----

    @Test
    void evaluateTop_higherPriorityWins() {
        TFlogYieldRule lowFirst = rule(1L, "R_LO", 5, "50", "80", "100", "2026-01-01 00:00:00", null);
        TFlogYieldRule high = rule(2L, "R_HI", 9, "40", "80", "100", "2026-01-01 00:00:00", null);
        when(mapper.selectList(any())).thenReturn(listOf(lowFirst, high));
        // 45：高顺位规则（线 40）判 2 档；若错按升序取 50 那根会判 1 档
        assertEquals(2, service.evaluateTop(new BigDecimal("45"), at));
    }

    // ---- 谜面7：权重相同时没定怎么挑，同一单跑两回档不同 ----

    @Test
    void evaluateTop_equalPriority_isDeterministicRegardlessOfDbOrder() {
        TFlogYieldRule a = rule(1L, "R_A", 5, "40", "80", "100", "2026-01-01 00:00:00", null);
        TFlogYieldRule b = rule(2L, "R_B", 5, "50", "80", "100", "2026-01-01 00:00:00", null);
        BigDecimal qty = new BigDecimal("45"); // A 线判 2、B 线判 1
        when(mapper.selectList(any())).thenReturn(listOf(a, b));
        int first = service.evaluateTop(qty, at);
        when(mapper.selectList(any())).thenReturn(listOf(b, a)); // 模拟 DB 无 ORDER BY 的另一回返回序
        int second = service.evaluateTop(qty, at);
        assertEquals(first, second, "同一单跑两回，档位必须相同");
        assertEquals(1, first, "平局按 ruleCode 降序 → R_B（线 50）→ 1 档");
    }

    // ---- 谜面9：换版当日按哪版判（eff_start 含 / eff_end 不含）----

    @Test
    void listAvailable_handoverDay_onlyNewVersionCounts() {
        TFlogYieldRule oldV = rule(1L, "R1", 1, "40", "80", "100",
                "2026-01-01 00:00:00", "2026-09-30 00:00:00");
        TFlogYieldRule newV = rule(2L, "R1", 1, "50", "80", "100",
                "2026-09-30 00:00:00", null);
        when(mapper.selectList(any())).thenReturn(listOf(oldV, newV));
        Date handover = t("2026-09-30 00:00:00");
        assertEquals(1, service.countAvailable(handover), "交棒当日旧版已不含，只数新版");
        assertEquals(1, service.listAvailable(handover).size());
        assertEquals(2L, service.listAvailable(handover).get(0).getId());
        assertEquals(1, service.evaluate("R1", new BigDecimal("45"), handover),
                "45 对新版线 50 判 1 档");
    }

    @Test
    void evaluate_bothVersionsEnded_returnsZero_notFutureVersion() {
        TFlogYieldRule oldV = rule(1L, "R1", 1, "40", "80", "100",
                "2026-01-01 00:00:00", "2026-09-30 00:00:00");
        TFlogYieldRule newV = rule(2L, "R1", 1, "50", "80", "100",
                "2026-09-30 00:00:00", "2027-01-01 00:00:00");
        when(mapper.selectList(any())).thenReturn(listOf(oldV, newV));
        assertEquals(0, service.evaluate("R1", new BigDecimal("45"), t("2027-06-01 00:00:00")),
                "两版都已交棒，无生效版本，不得兜底捞到任一版");
    }

    @Test
    void effStartBoundary_isInclusive() {
        TFlogYieldRule r = rule(1L, "R1", 1, "40", "80", "100",
                "2026-09-15 10:00:00", null);
        when(mapper.selectList(any())).thenReturn(listOf(r));
        assertEquals(1, service.countAvailable(t("2026-09-15 10:00:00")), "启用那一刻即生效");
        assertEquals(0, service.countAvailable(t("2026-09-15 09:59:59")), "启用前一刻不生效");
    }

    // ---- 谜面2：统计规矩有差（列表曾截断 200，与计数凑不拢）----

    @Test
    void listAndCount_doNotTruncate_andAgree() {
        List<TFlogYieldRule> many = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            many.add(rule(i, "R" + String.format("%03d", i), 1,
                    "40", "80", "100", "2026-01-01 00:00:00", null));
        }
        when(mapper.selectList(any())).thenReturn(many);
        assertEquals(201, service.countAvailable(at));
        assertEquals(201, service.listAvailable(at).size(), "列表与角标计数必须同一口径");
    }

    // ---- 谜面10：覆盖范围判断凑不拢（usable 漏 del_flag；无效阈值规则混入列表）----

    @Test
    void usable_rejectsDeleted_evenThoughSelectByIdDoesNotFilter() {
        TFlogYieldRule deleted = rule(1L, "R1", 1, "40", "80", "100",
                "2026-01-01 00:00:00", null);
        deleted.setDelFlag(1);
        when(mapper.selectById(1L)).thenReturn(deleted);
        assertFalse(service.usable(1L, at), "已删除规则不可用");
    }

    @Test
    void coverage_isConsistent_acrossListCountAndUsable() {
        TFlogYieldRule ok = rule(1L, "R_OK", 1, "40", "80", "100", "2026-01-01 00:00:00", null);
        TFlogYieldRule retired = rule(2L, "R_OLD", 1, "40", "80", "100", "2026-01-01 00:00:00", null);
        retired.setStatus(1);
        TFlogYieldRule noThresholds = rule(3L, "R_EMPTY", 1, null, null, null,
                "2026-01-01 00:00:00", null);
        TFlogYieldRule badTh3 = rule(4L, "R_BAD", 1, "40", "80", "120",
                "2026-01-01 00:00:00", null);
        when(mapper.selectList(any())).thenReturn(listOf(ok, retired, noThresholds, badTh3));
        assertEquals(1, service.countAvailable(at));
        assertEquals(1, service.listAvailable(at).size());
        when(mapper.selectById(1L)).thenReturn(ok);
        assertTrue(service.usable(1L, at));
        when(mapper.selectById(3L)).thenReturn(noThresholds);
        assertFalse(service.usable(3L, at), "三条线全缺的规则，列表/计数/可用三处都不认");
        when(mapper.selectById(4L)).thenReturn(badTh3);
        assertFalse(service.usable(4L, at), "重度线上限越界，三处都不认");
    }

    private static List<TFlogYieldRule> listOf(TFlogYieldRule... rs) {
        List<TFlogYieldRule> l = new ArrayList<>();
        for (TFlogYieldRule r : rs) {
            l.add(r);
        }
        return l;
    }
}
