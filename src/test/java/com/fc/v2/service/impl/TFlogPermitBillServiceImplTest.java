package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;

/**
 * 逐级核签单状态口径（报批情形 0在核 1已核讫 2已打回）：
 * 否决必须落成「已打回」；退回上一环节必须把状态带回「在核」；
 * 已核讫不得再否决/退回。
 */
@ExtendWith(MockitoExtension.class)
class TFlogPermitBillServiceImplTest {

    @Mock
    private TFlogPermitBillMapper mapper;

    @InjectMocks
    private TFlogPermitBillServiceImpl service;

    private TFlogPermitBill bill(Integer node, Integer status) {
        TFlogPermitBill r = new TFlogPermitBill();
        r.setId(9L);
        r.setBillNo("P9");
        r.setNodeNo(node);
        r.setStatus(status);
        return r;
    }

    @Test
    void reject_marksBillVetoed_notLeftRunning() {
        when(mapper.selectById(9L)).thenReturn(bill(1, 0));
        TFlogPermitBill r = service.reject(9L, "张工", "材料不齐");
        assertEquals(2, r.getStatus().intValue(), "否决即「已打回」，不得还挂在核");
    }

    @Test
    void reject_requiresApprover() {
        assertNull(service.reject(9L, "  ", "x"), "签批人为空直接拒绝，不查库");
    }

    @Test
    void reject_afterPassed_isRejected() {
        when(mapper.selectById(9L)).thenReturn(bill(2, 1));
        assertNull(service.reject(9L, "张工", "x"), "已核讫不得再否决");
    }

    @Test
    void rollback_afterVeto_returnsToRunning_andOneLevelBack() {
        when(mapper.selectById(9L)).thenReturn(bill(2, 2));
        TFlogPermitBill r = service.rollback(9L, "退回补料");
        assertEquals(1, r.getNodeNo().intValue(), "退回上一环节：级次 -1");
        assertEquals(0, r.getStatus().intValue(), "退回后状态必须带回「在核」，与超时/打回口径区分开");
    }

    @Test
    void rollback_atEntry_isRejected() {
        when(mapper.selectById(9L)).thenReturn(bill(0, 0));
        assertNull(service.rollback(9L, "x"), "首级无环节可退");
    }

    @Test
    void approve_climbsOneLevelAndPassesAtTop() {
        when(mapper.selectById(9L)).thenReturn(bill(0, 0));
        TFlogPermitBill r = service.approve(9L, "张工", "ok");
        assertEquals(1, r.getNodeNo().intValue());
        assertEquals(0, r.getStatus().intValue(), "未到顶仍在核");

        when(mapper.selectById(9L)).thenReturn(bill(1, 0));
        r = service.approve(9L, "李工", "ok");
        assertEquals(2, r.getNodeNo().intValue());
        assertEquals(1, r.getStatus().intValue(), "到顶即核讫");
    }
}
