package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;
import com.fc.v2.service.ITFlogCloseCardService;

/**
 * 伐区勾销事务卡 Service业务层处理（state-machine 形状：单据流转）。
 * 段次 0报伐/1核株/2验更/3了断；落定 0未起 1在办 2已了断。
 * 推进/回退均一次一段；走到了断段即收口为已了断；已了断任何动作一律拒绝。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogCloseCardServiceImpl implements ITFlogCloseCardService {

    private static final int MAX_STAGE = 3;
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
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        int next = st + 1;
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(remark);
        this.flogCloseCardMapper.updateById(r);
        return r;
    }

    @Override
    public TFlogCloseCard rollback(Long id, String remark) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.flogCloseCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        r.setContent(remark);
        return this.flogCloseCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        return this.flogCloseCardMapper.deleteById(id) > 0;
    }

    private boolean isTerminal(TFlogCloseCard r) {
        return r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL;
    }
}
