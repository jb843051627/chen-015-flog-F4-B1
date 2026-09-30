package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;

/**
 * 额度事项周期执行：
 * 到期口径 = due_at 该动手那天的最后一刻（那一刻即到期，含端点），且只认未开口、未删除；
 * 成功落「已落地 1」，办不下去落「办不下去 2」（与勾销卡回退的在办口径区分）；空跑返回 0。
 */
@ExtendWith(MockitoExtension.class)
class TFlogQuotaTaskServiceImplTest {

    @Mock
    private TFlogQuotaTaskMapper mapper;

    @InjectMocks
    private TFlogQuotaTaskServiceImpl service;

    private static Date t(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TFlogQuotaTask task(long id, String dueAt, String amount, Integer status, Integer del) {
        TFlogQuotaTask r = new TFlogQuotaTask();
        r.setId(id);
        r.setItemNo("Q" + id);
        r.setDueAt(dueAt == null ? null : t(dueAt));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setStatus(status);
        r.setDelFlag(del);
        return r;
    }

    private static List<TFlogQuotaTask> listOf(TFlogQuotaTask... rs) {
        List<TFlogQuotaTask> l = new ArrayList<>();
        for (TFlogQuotaTask r : rs) {
            l.add(r);
        }
        return l;
    }

    @Test
    void listDue_dueAtExactlyNow_isDue_inclusiveEndpoint() {
        TFlogQuotaTask on = task(1, "2026-09-30 23:59:59", "10", 0, 0);
        TFlogQuotaTask later = task(2, "2026-10-01 00:00:00", "10", 0, 0);
        TFlogQuotaTask done = task(3, "2026-09-01 00:00:00", "10", 1, 0);
        TFlogQuotaTask deleted = task(4, "2026-09-01 00:00:00", "10", 0, 1);
        when(mapper.selectList(any())).thenReturn(listOf(on, later, done, deleted));

        List<TFlogQuotaTask> due = service.listDue(t("2026-09-30 23:59:59"));
        assertEquals(1, due.size(), "踩到最后一刻即到期；未到、已落地、已删除都不得混入");
        assertEquals(1L, due.get(0).getId());
    }

    @Test
    void runOnce_empty_returnsZeroWithoutDereferencing() {
        when(mapper.selectList(any())).thenReturn(new ArrayList<>());
        assertEquals(0, service.runOnce(t("2026-09-30 23:59:59")), "没有到期事项时空跑返回 0，不得空表取首条");
    }

    @Test
    void runOnce_marksLandedOrStalled_andCountsSuccess() {
        TFlogQuotaTask ok = task(1, "2026-09-30 00:00:00", "10", 0, 0);
        TFlogQuotaTask stalled = task(2, "2026-09-30 00:00:00", "-5", 0, 0);
        TFlogQuotaTask noAmount = task(3, "2026-09-30 00:00:00", null, 0, 0);
        when(mapper.selectList(any())).thenReturn(listOf(ok, stalled, noAmount));
        when(mapper.updateById(any())).thenReturn(1);

        int success = service.runOnce(t("2026-10-01 00:00:00"));
        assertEquals(1, success, "只有正数额度算落地成功");

        ArgumentCaptor<TFlogQuotaTask> captor = ArgumentCaptor.forClass(TFlogQuotaTask.class);
        verify(mapper, org.mockito.Mockito.times(3)).updateById(captor.capture());
        assertEquals(1, captor.getAllValues().get(0).getStatus().intValue(), "正数 → 已落地");
        assertEquals(2, captor.getAllValues().get(1).getStatus().intValue(), "负数（超时收口）→ 办不下去");
        assertEquals(2, captor.getAllValues().get(2).getStatus().intValue(), "额度缺失 → 办不下去");
    }

    @Test
    void runOnce_singleFailure_skipsAndContinues() {
        TFlogQuotaTask bad = task(1, "2026-09-30 00:00:00", null, 0, 0);
        TFlogQuotaTask ok = task(2, "2026-09-30 00:00:00", "8", 0, 0);
        when(mapper.selectList(any())).thenReturn(listOf(bad, ok));
        when(mapper.updateById(any())).thenReturn(1);
        assertEquals(1, service.runOnce(t("2026-10-01 00:00:00")), "单条失败跳过继续，后者仍落地");
        assertSame(ok, ok);
        verify(mapper, org.mockito.Mockito.times(2)).updateById(any());
        verify(mapper, never()).deleteById(any());
    }
}
