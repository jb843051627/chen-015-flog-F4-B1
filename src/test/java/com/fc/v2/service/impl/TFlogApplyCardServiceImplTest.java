package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
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
 * 建档卡拟采蓄积分档复现测试。权威口径见 doc/schema/flog.sql 种子注释：
 * 单卡蓄积三线上限 40/120/300 立方米，等于上限算高一档。
 * 底档阈值必须取自同一条经 del_flag+status 校验的在册底档（两处查询不得各取一条）。
 */
@ExtendWith(MockitoExtension.class)
class TFlogApplyCardServiceImplTest {

    @Mock
    private TFlogApplyCardMapper baseMapper;

    @Mock
    private TFlogStandDocMapper standMapper;

    @InjectMocks
    private TFlogApplyCardServiceImpl service;

    private TFlogStandDoc stand;

    @BeforeEach
    void wire() {
        stand = new TFlogStandDoc();
        stand.setId(0L);
        stand.setSiteNo("DC00");
        stand.setStatus(0);
        stand.setDelFlag(0);
        stand.setTh1Max(new BigDecimal("40"));
        stand.setTh2Max(new BigDecimal("120"));
        stand.setTh3Max(new BigDecimal("300"));
        lenient().when(standMapper.selectOne(any())).thenReturn(stand);
        lenient().when(baseMapper.selectCount(any())).thenReturn(0);
        lenient().when(baseMapper.insert(any())).thenReturn(1);
    }

    private TFlogApplyCard card(String qty) {
        TFlogApplyCard r = new TFlogApplyCard();
        r.setBillNo("B1");
        r.setSiteId(0);
        if (qty != null) {
            r.setQty(new BigDecimal(qty));
        }
        return r;
    }

    // 症状1/2：踩着线的该上没上；分档规矩与规则线两处有差
    @Test
    void equalToVolumeThresholdGoesUpOneTier() {
        TFlogApplyCard c40 = card("40");
        service.insertTFlogApplyCard(c40);
        assertEquals(2, c40.getGradeLevel(), "蓄积 40 恰等于轻度上限应入 2 档");

        TFlogApplyCard c120 = card("120");
        service.insertTFlogApplyCard(c120);
        assertEquals(3, c120.getGradeLevel(), "蓄积 120 应入 3 档");

        TFlogApplyCard c300 = card("300");
        service.insertTFlogApplyCard(c300);
        assertEquals(4, c300.getGradeLevel(), "蓄积 300 应入 4 档");

        TFlogApplyCard c39 = card("39.99");
        service.insertTFlogApplyCard(c39);
        assertEquals(1, c39.getGradeLevel(), "差一线仍在 1 档");
    }

    // 症状6/8：缺阈值不得 NPE；同一单跑两回档相同
    @Test
    void missingFirstThresholdIsSafeAndDeterministic() {
        stand.setTh1Max(null);
        TFlogApplyCard c1 = card("40");
        TFlogApplyCard c2 = card("40");
        service.insertTFlogApplyCard(c1);
        service.insertTFlogApplyCard(c2);
        assertEquals(2, c1.getGradeLevel(), "轻度上限缺失时 40 落入中度档，不得抛错");
        assertEquals(c1.getGradeLevel(), c2.getGradeLevel(), "同一单跑两回档位必须一致");
    }

    // 症状2/5：阈值只认同一条经校验的在册底档，不得二次 selectById 另取
    @Test
    void thresholdsComeFromTheSingleValidatedStand() {
        TFlogStandDoc rogue = new TFlogStandDoc();
        rogue.setId(0L);
        rogue.setStatus(0);
        rogue.setDelFlag(0);
        rogue.setTh1Max(new BigDecimal("10"));
        rogue.setTh2Max(new BigDecimal("20"));
        rogue.setTh3Max(new BigDecimal("30"));
        // 旧实现第二次 selectById 若另取一条，会把 40 判到 4 档
        lenient().when(standMapper.selectById(any())).thenReturn(rogue);

        TFlogApplyCard c = card("40");
        service.insertTFlogApplyCard(c);
        assertEquals(2, c.getGradeLevel(), "应以校验过的在册底档 40/120/300 为准");
        verify(standMapper, never()).selectById(any());
    }

    // 已收回底档不得建档
    @Test
    void revokedStandRejected() {
        stand.setStatus(1);
        TFlogApplyCard c = card("40");
        assertEquals(0, service.insertTFlogApplyCard(c));
        assertNull(c.getGradeLevel(), "已收回底档不得建档位");
    }
}
