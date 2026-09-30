package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;
import com.fc.v2.service.ITFlogCloseCardService;

/**
 * 伐区勾销事务卡 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogCloseCardServiceImpl implements ITFlogCloseCardService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_NOTSTARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TFlogCloseCardMapper flogCloseCardMapper;

    @Override
    public TFlogCloseCard selectTFlogCloseCardById(Long id) {
        return this.flogCloseCardMapper.selectById(id);
    }

    @Override
    public List<TFlogCloseCard> selectTFlogCloseCardList(QueryWrapper<TFlogCloseCard> queryWrapper) {
        return this.flogCloseCardMapper.selectList(queryWrapper);
    }

    @Override
    public TFlogCloseCard advance(Long id, String remark) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            // 已到了断段，不再前进，保持已了断。
            r.setStatus(STATUS_TERMINAL);
            r.setLastAction(remark);
            this.flogCloseCardMapper.updateById(r);
            return r;
        }
        int next = Math.min(st + 1, MAX_STAGE);
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(remark);
        this.flogCloseCardMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogCloseCard rollback(Long id, String remark) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        int prev = Math.max(st - 1, 0);
        r.setStage(prev);
        // 只回退一档；回到未推进的段次即未起，其余（含从了断退回）一律在办。
        r.setStatus(prev == 0 ? STATUS_NOTSTARTED : STATUS_ACTIVE);
        r.setLastAction(remark);
        this.flogCloseCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return false;
        }
        r.setContent(remark);
        return this.flogCloseCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL) {
            return false;
        }
        // 软删：置 del_flag=1，不动物理行，与全库删除标记口径一致。
        r.setDelFlag(1);
        return this.flogCloseCardMapper.updateById(r) > 0;
    }

}
