package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;

/**
 * 可采强度界限值线 口径复现测试。权威口径见 doc/schema/flog.sql：
 * 等于上限算高一档；交棒之日(不含)；priority 数值越大越先开口；
 * 接口注释：listAvailable 优先级降序、ruleCode 降序；usable 口径须与列表/定位一致。
 */
@ExtendWith(MockitoExtension.class)
class TFlogYieldRuleServiceImplTest {

    @Mock
    private TFlogYieldRuleMapper mapper;

    @InjectMocks
    private TFlogYieldRuleServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private List<TFlogYieldRule> db = new ArrayList<>();

    private Date t(String s) throws Exception {
        return f.parse(s);
    }

    private TFlogYieldRule rule(long id, String code, int pri,
                                String th1, String th2, String th3,
                                String start, String end) {
        TFlogYieldRule r = new TFlogYieldRule();
        r.setId(id);
        r.setRuleCode(code);
        r.setPriority(pri);
        r.setStatus(0);
        r.setDelFlag(0);
        if (th1 != null) r.setTh1Max(new BigDecimal(th1));
        if (th2 != null) r.setTh2Max(new BigDecimal(th2));
        if (th3 != null) r.setTh3Max(new BigDecimal(th3));
        try {
            if (start != null) r.setEffStart(t(start));
            if (end != null) r.setEffEnd(t(end));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    @BeforeEach
    void wire() {
        // 模拟 SQL：带 rule_code 的查询按代号过滤；否则返回全集。测试数据均 status=0/del_flag=0。
        // 规则线阈值为强度口径（立方米/公顷，0..100）；建档底档 40/120/300 是蓄积量，两套单位不同。
        lenient().when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TFlogYieldRule> w = inv.getArgument(0);
            Collection<Object> vals = w.getParamNameValuePairs().values();
            String code = vals.stream().filter(v -> v instanceof String)
                    .map(Object::toString).findFirst().orElse(null);
            if (code != null) {
                return db.stream().filter(r -> code.equals(r.getRuleCode()))
                        .collect(Collectors.toList());
            }
            return new ArrayList<>(db);
        });
        lenient().when(mapper.selectById(any())).thenAnswer(inv -> {
            Long id = ((Number) inv.getArgument(0)).longValue();
            return db.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        });
    }

    // 症状1：踩着线的该上没上 —— 等于上限必须算高一档
    @Test
    void equalToThresholdGoesUpOneTier() throws Exception {
        db.add(rule(1, "R", 1, "20", "50", "80", "2026-01-01 00:00:00", null));
        Date at = t("2026-06-01 00:00:00");
        assertEquals(2, service.evaluate("R", new BigDecimal("20"), at), "等于轻度上限应入中度(2)");
        assertEquals(3, service.evaluate("R", new BigDecimal("50"), at), "等于中度上限应入重度(3)");
        assertEquals(4, service.evaluate("R", new BigDecimal("80"), at), "等于重度上限应入4档");
        assertEquals(1, service.evaluate("R", new BigDecimal("19.99"), at), "差一线仍在轻度(1)");
    }

    // 症状3：择优先后同档案优先级掉个 —— 数值大者优先
    @Test
    void higherPriorityWins() throws Exception {
        db.add(rule(1, "LOW", 1, "20", "50", "80", "2026-01-01 00:00:00", null));
        db.add(rule(2, "HIGH", 10, "20", "50", "80", "2026-01-01 00:00:00", null));
        Date at = t("2026-06-01 00:00:00");
        List<TFlogYieldRule> avail = service.listAvailable(at);
        assertEquals("HIGH", avail.get(0).getRuleCode(), "优先级 10 应排在 1 之前");
        assertEquals(2, service.evaluateTop(new BigDecimal("20"), at), "取优先级最高者判档，40 应升为 2");
    }

    // 症状8：权重相同没定怎么挑 —— 同优先级按 ruleCode 降序，确定不漂移
    @Test
    void equalPriorityTieBreakByRuleCodeDesc() throws Exception {
        db.add(rule(1, "AAA", 5, "20", "50", "80", "2026-01-01 00:00:00", null));
        db.add(rule(2, "BBB", 5, "20", "50", "80", "2026-01-01 00:00:00", null));
        Date at = t("2026-06-01 00:00:00");
        List<TFlogYieldRule> a1 = service.listAvailable(at);
        List<TFlogYieldRule> a2 = service.listAvailable(at);
        assertEquals("BBB", a1.get(0).getRuleCode(), "同优先级按 ruleCode 降序");
        assertEquals(a1.get(0).getId(), a2.get(0).getId(), "同一单跑两回结果必须一致");
    }

    // 症状10：交棒之日(不含) —— 恰好交棒那一刻归新版/旧版必须钉死：旧版已不含
    @Test
    void endDayIsExclusive() throws Exception {
        db.add(rule(1, "R", 1, "20", "50", "80",
                "2026-01-01 00:00:00", "2026-09-30 00:00:00"));
        assertEquals(0, service.evaluate("R", new BigDecimal("10"), t("2026-09-30 00:00:00")),
                "交棒当日 00:00:00 旧版已不含，无新版应判 0");
        assertFalse(service.usable(1L, t("2026-09-30 00:00:00")), "交棒当日不可用");
        assertEquals(0, service.countAvailable(t("2026-09-30 00:00:00")));
    }

    // 启用之日含
    @Test
    void startDayIsInclusive() throws Exception {
        db.add(rule(1, "R", 1, "20", "50", "80",
                "2026-09-30 08:00:00", null));
        assertTrue(service.usable(1L, t("2026-09-30 08:00:00")), "启用当日当时刻应可用");
        assertFalse(service.usable(1L, t("2026-09-29 23:59:59")), "启用前不可用");
    }

    // 症状11/2：覆盖口径一致 —— 无阈值不可用；count/list/usable 凑得拢
    @Test
    void coverageConsistency() throws Exception {
        db.add(rule(1, "FULL", 1, "20", "50", "80", "2026-01-01 00:00:00", null));
        db.add(rule(2, "NOTH", 2, null, null, null, "2026-01-01 00:00:00", null));
        Date at = t("2026-06-01 00:00:00");
        assertTrue(service.usable(1L, at));
        assertFalse(service.usable(2L, at), "三条阈值全缺不可用");
        // 角标口径：可用只数真正能用的（无阈值不算）
        assertEquals(1, service.countAvailable(at), "覆盖计数应只算可用规则");
        assertEquals(1, service.listAvailable(at).size(), "列表与计数覆盖范围一致");
    }

    // 症状10：换版当日按哪版 —— 新旧版本重叠时取启用日最新者；仅旧版已过期不得兜底取旧版
    @Test
    void expiredVersionIsNotUsedAsFallback() throws Exception {
        db.add(rule(1, "R", 1, "20", "50", "80",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00"));
        assertEquals(0, service.evaluate("R", new BigDecimal("10"), t("2026-06-01 00:00:00")),
                "仅有已失效旧版时应判 0，不得回退拿旧版开口子");
    }
}
