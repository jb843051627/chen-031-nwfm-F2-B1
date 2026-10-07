package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.service.ITNwfmCheckRowService;

/**
 * 周期核收条目 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmCheckRowServiceImpl implements ITNwfmCheckRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TNwfmCheckRowMapper nwfmCheckRowMapper;

    @Override
    public TNwfmCheckRow selectTNwfmCheckRowById(Long id) {
        if (id == null) {
            return null;
        }
        return this.nwfmCheckRowMapper.selectById(id);
    }

    /**
     * 整批提交。
     * 规矩（按一线登账口径）：
     * 1) 进门先点行数：空批回 0；超过单批上限回 -1，两种情况整批一笔都不落，让拆批重交；
     * 2) 同一册次码只收一回：批次已存在直接幂等返回，不再重复入库；
     * 3) 行号按送验清单的物理次序从 1 起逐行连续编号，合法行也占号——
     *    退单报的行号必须和纸质底单上的行次对得上；
     * 4) 逐行校验：非法行只落失败明细（status=2，保留原始行号）供 listErrors 回查，
     *    只要有一行对不上，整批合法行一律不入库，返回 0。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TNwfmCheckRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        // 只认成功行封批：批次已有合法行入库即幂等返回，绝不重复入库；
        // 若上次整批退回、只剩失败明细，删掉旧明细后允许改妥重交。
        Integer okExisted = this.nwfmCheckRowMapper.selectCount(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_OK).eq("del_flag", 0));
        if (okExisted != null && okExisted.intValue() > 0) {
            return okExisted.intValue();
        }
        this.nwfmCheckRowMapper.delete(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL).eq("del_flag", 0));

        boolean hasError = false;
        int seq = 0;
        // 同一回里同一摊位的条目代号只该留一条：第二次露面按非法行退回
        java.util.Set<String> seenKeys = new java.util.HashSet<String>();
        for (TNwfmCheckRow r : rows) {
            seq++;
            r.setRowNo(Integer.valueOf(seq));
            r.setBatchNo(batchNo);
            if (r.getDelFlag() == null) {
                r.setDelFlag(Integer.valueOf(0));
            }
            String stall = r.getStallName() == null ? "" : r.getStallName().trim();
            String code = r.getItemCode() == null ? "" : r.getItemCode().trim();
            String dedupKey = stall + "#" + code;
            boolean duplicate = !code.isEmpty() && !seenKeys.add(dedupKey);
            if (duplicate || !isValid(r)) {
                r.setStatus(Integer.valueOf(STATUS_FAIL));
                this.nwfmCheckRowMapper.insert(r);
                hasError = true;
            }
        }
        if (hasError) {
            return 0;
        }

        int ok = 0;
        for (TNwfmCheckRow r : rows) {
            r.setStatus(Integer.valueOf(STATUS_OK));
            this.nwfmCheckRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<TNwfmCheckRow> listErrors(String batchNo) {
        return this.nwfmCheckRowMapper.selectList(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0)
                .orderByAsc("row_no"));
    }

    private boolean isValid(TNwfmCheckRow r) {
        return r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                && r.getQty() != null && r.getQty().compareTo(BigDecimal.ZERO) > 0;
    }
}
