package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

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

import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;

/**
 * 额度事项到期/超时复现测试。due_at = 该动手那天的最后一刻。
 * 到期判定：到了"最后一刻"就算到期（含边界）；只列尚未处理项；
 * runOnce 的覆盖范围须与 listDue 同一份口径，落库须置已落地(1)，空表不得越界。
 */
@ExtendWith(MockitoExtension.class)
class TFlogQuotaTaskServiceImplTest {

    @Mock
    private TFlogQuotaTaskMapper mapper;

    @InjectMocks
    private TFlogQuotaTaskServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final List<TFlogQuotaTask> db = new ArrayList<>();

    private Date t(String s) throws Exception {
        return f.parse(s);
    }

    private TFlogQuotaTask task(long id, String dueAt, Integer status) throws Exception {
        TFlogQuotaTask r = new TFlogQuotaTask();
        r.setId(id);
        r.setDueAt(t(dueAt));
        r.setAmount(new BigDecimal("3"));
        r.setStatus(status);
        r.setDelFlag(0);
        db.add(r);
        return r;
    }

    @BeforeEach
    void wire() {
        lenient().when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(db));
        lenient().when(mapper.updateById(any())).thenAnswer(inv -> 1);
    }

    // 症状1/超时边界：恰好"最后一刻"到期必须算到期（含边界）
    @Test
    void dueAtLastMomentIsDue() throws Exception {
        task(1, "2026-09-30 23:59:59", 0);
        List<TFlogQuotaTask> due = service.listDue(t("2026-09-30 23:59:59"));
        assertEquals(1, due.size(), "恰等到期最后一刻应到期");
        assertTrue(service.listDue(t("2026-10-01 00:00:00")).stream().anyMatch(x -> x.getId() == 1L),
                "最后一刻之后仍到期");
        assertFalse(service.listDue(t("2026-09-30 23:59:58")).stream().anyMatch(x -> x.getId() == 1L),
                "差一秒未到期");
    }

    // 已落地 / 办不下去 的不再列
    @Test
    void handledTasksAreNotDueAgain() throws Exception {
        task(1, "2026-09-01 00:00:00", 1);
        task(2, "2026-09-01 00:00:00", 2);
        task(3, "2026-09-01 00:00:00", 0);
        List<TFlogQuotaTask> due = service.listDue(t("2026-09-30 00:00:00"));
        assertEquals(1, due.size());
        assertEquals(3L, due.get(0).getId());
    }

    // 空表安全
    @Test
    void runOnceOnEmptyIsZero() {
        assertEquals(0, service.runOnce(new Date(0)));
    }

    // 落库置已落地，且只办未办项；runOnce 覆盖口径与 listDue 一致
    @Test
    void runOnceMarksDoneAndSkipsHandled() throws Exception {
        task(1, "2026-09-01 00:00:00", 1);
        task(2, "2026-09-01 00:00:00", 0);
        int ok = service.runOnce(t("2026-09-30 00:00:00"));
        assertEquals(1, ok, "只办未办的 1 条");
        TFlogQuotaTask already = db.stream().filter(x -> x.getId() == 1L).findFirst().get();
        TFlogQuotaTask handled = db.stream().filter(x -> x.getId() == 2L).findFirst().get();
        assertEquals(1, already.getStatus().intValue(), "已落地的不动");
        assertEquals(1, handled.getStatus().intValue(), "本次办到的条目须落库置已落地(1)");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).updateById(handled);
    }
}
