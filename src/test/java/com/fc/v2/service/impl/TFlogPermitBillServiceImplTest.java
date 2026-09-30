package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;

/**
 * 核签单状态流转复现测试。报批情形 0在核 1已核讫 2已打回；当前所在级 0..3。
 * 否决必须落"已打回(2)"；退回上一环节必须把状态拉回"在核(0)"，与勾销卡回退口径对齐。
 */
@ExtendWith(MockitoExtension.class)
class TFlogPermitBillServiceImplTest {

    @Mock
    private TFlogPermitBillMapper mapper;

    @InjectMocks
    private TFlogPermitBillServiceImpl service;

    private final Map<Long, TFlogPermitBill> db = new HashMap<>();

    @BeforeEach
    void wire() {
        org.mockito.Mockito.lenient().when(mapper.selectById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> db.get(((Number) inv.getArgument(0)).longValue()));
        org.mockito.Mockito.lenient().when(mapper.updateById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> 1);
    }

    private TFlogPermitBill bill(long id, Integer node, Integer status) {
        TFlogPermitBill r = new TFlogPermitBill();
        r.setId(id);
        r.setNodeNo(node);
        r.setStatus(status);
        r.setDelFlag(0);
        db.put(id, r);
        return r;
    }

    // 症状5/7：逐级签批到顶落"已核讫"
    @Test
    void approveThroughLevelsPasses() {
        bill(1, 0, 0);
        TFlogPermitBill r1 = service.approve(1L, "zhang", "ok");
        assertEquals(1, r1.getNodeNo());
        assertEquals(0, r1.getStatus(), "第一级签完仍在核");
        TFlogPermitBill r2 = service.approve(1L, "li", "ok");
        assertEquals(2, r2.getNodeNo());
        assertEquals(1, r2.getStatus(), "到顶应已核讫(1)");
    }

    // 症状5：否决必须落"已打回(2)"，旧实现只 update 不改状态
    @Test
    void rejectMarksVeto() {
        bill(1, 1, 0);
        TFlogPermitBill r = service.reject(1L, "wang", "no");
        assertNotNull(r);
        assertEquals(2, r.getStatus(), "否决应落已打回(2)");
    }

    // 症状5/7：退回上一环节，级回退且状态拉回在核(0)
    @Test
    void rollbackPullsStatusBackToRunning() {
        bill(1, 2, 1);
        TFlogPermitBill r = service.rollback(1L, "back");
        assertEquals(1, r.getNodeNo(), "回退一级 2→1");
        assertEquals(0, r.getStatus(), "回退后状态必须拉回在核(0)，不能仍挂已核讫");
    }

    // 已在首级再退，不落负
    @Test
    void rollbackAtFirstLevelStaysZero() {
        bill(1, 0, 0);
        TFlogPermitBill r = service.rollback(1L, "back");
        assertEquals(0, r.getNodeNo());
        assertEquals(0, r.getStatus());
    }
}
