package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;

/**
 * 申请建档卡分档：必须与界限值线同一套规矩（档案：等于上限算高一档），
 * 底档阈值缺失时不得抛 NPE（两处共用同一判档规矩）。
 */
@ExtendWith(MockitoExtension.class)
class TFlogApplyCardServiceImplTest {

    @Mock
    private TFlogApplyCardMapper baseMapper;

    @Mock
    private TFlogStandDocMapper flogStandDocMapper;

    @InjectMocks
    private TFlogApplyCardServiceImpl service;

    private TFlogStandDoc arch(String th1, String th2, String th3) {
        TFlogStandDoc a = new TFlogStandDoc();
        a.setId(0L);
        a.setSiteNo("DC00");
        a.setStatus(0);
        a.setDelFlag(0);
        a.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        a.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        a.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        return a;
    }

    private TFlogApplyCard card(String qty) {
        TFlogApplyCard c = new TFlogApplyCard();
        c.setId(100L);
        c.setBillNo("B1");
        c.setSiteId(0);
        c.setQty(qty == null ? null : new BigDecimal(qty));
        return c;
    }

    @Test
    void insert_boundaryEqualsUpper_goesHigherTier_sameAsRuleService() {
        TFlogStandDoc a = arch("40", "120", "300");
        when(flogStandDocMapper.selectOne(any())).thenReturn(a);
        when(flogStandDocMapper.selectById(any())).thenReturn(a);
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(baseMapper.insert(any())).thenReturn(1);

        assertEquals(1, gradeOf("39.00"), "39 < 40 → 1 档");
        assertEquals(2, gradeOf("40.00"), "40 踩一线 → 2 档（与界限值线口径一致）");
        assertEquals(2, gradeOf("119.99"));
        assertEquals(3, gradeOf("120.00"), "120 踩二线 → 3 档");
        assertEquals(3, gradeOf("299.99"));
        assertEquals(4, gradeOf("300.00"), "300 踩三线 → 4 档");
    }

    @Test
    void insert_missingThresholds_doesNotThrow() {
        TFlogStandDoc a = arch(null, null, null);
        when(flogStandDocMapper.selectOne(any())).thenReturn(a);
        when(flogStandDocMapper.selectById(any())).thenReturn(a);
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(baseMapper.insert(any())).thenReturn(1);

        TFlogApplyCard c = card("10");
        assertEquals(1, service.insertTFlogApplyCard(c), "阈值全缺也不得 NPE 绕手工");
        assertEquals(0, c.getGradeLevel().intValue(), "三条线皆空 → 无法判档留 0（与界限值线一致），不得乱归档");
    }

    @Test
    void insert_retiredArch_rejected() {
        TFlogStandDoc a = arch("40", "120", "300");
        a.setStatus(1);
        when(flogStandDocMapper.selectOne(any())).thenReturn(a);
        assertEquals(0, service.insertTFlogApplyCard(card("10")), "已收回底档不得建档");
    }

    private int gradeOf(String qty) {
        TFlogApplyCard c = card(qty);
        service.insertTFlogApplyCard(c);
        return c.getGradeLevel();
    }
}
