package com.fc.v2.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        return this.nwfmCheckRowMapper.selectById(id);
    }

    /**
     * 整批提交：
     * 1) 空批次（null/0 行）返回 0——零行也给一句回话，不抛异常撂挑子；
     * 2) 行数超过单批上限 500 返回 -1，整批不入库，退回让经办人拆批重交；
     * 3) 同一册次码重复提交幂等：已核收入库过的直接返回既有成功行数，不翻倍；
     * 4) 逐行校验：合法的入库（已核），非法的记入失败明细，行号一律沿用单据上的
     *    "原始行次"（调用方没带行号才按序号兜底），不能报系统自个儿数出来的序号；
     * 5) 同一批次内同一摊位/条目号重复出现，只留一条，重复的记失败明细。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TNwfmCheckRow> rows) {
        // 报了一批、一行没有：给 0，不炸
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 一千多行照单全收不行：超上限整批退回，拆了再交
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = (batchNo != null && !batchNo.trim().isEmpty())
                ? batchNo.trim()
                : (rows.get(0).getBatchNo() == null ? null : rows.get(0).getBatchNo().trim());

        // 同册次码只交过一回：再交一遍直接回既有成功行数，不重复入库
        Integer existed = this.nwfmCheckRowMapper.selectCount(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", no).eq("status", STATUS_OK).eq("del_flag", 0));
        if (existed != null && existed.intValue() > 0) {
            return existed.intValue();
        }

        // 上次整核失败留下的失败明细先清掉，避免和这次的结果串味
        this.nwfmCheckRowMapper.delete(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", no).eq("status", STATUS_FAIL).eq("del_flag", 0));

        Set<String> seenItem = new HashSet<String>();
        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            TNwfmCheckRow r = rows.get(i);
            // 行次以纸质底单/表格上的原始行次为准；没带行号才按表里的序号兜底
            int originalRowNo = (r.getRowNo() != null) ? r.getRowNo().intValue() : (i + 1);
            r.setRowNo(Integer.valueOf(originalRowNo));
            r.setBatchNo(no);
            r.setDelFlag(Integer.valueOf(0));

            String itemCode = (r.getItemCode() == null) ? null : r.getItemCode().trim();
            boolean invalid = (itemCode == null || itemCode.isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0);
            if (invalid) {
                r.setStatus(Integer.valueOf(STATUS_FAIL));
                r.setRemark("条目代号为空或数量不合法");
                this.nwfmCheckRowMapper.insert(r);
                continue;
            }
            if (!seenItem.add(itemCode)) {
                // 一回里同一个摊位的号露两遍：只收头一条，重复的进失败明细
                r.setItemCode(itemCode);
                r.setStatus(Integer.valueOf(STATUS_FAIL));
                r.setRemark("同一册次内条目重复，只保留一条");
                this.nwfmCheckRowMapper.insert(r);
                continue;
            }
            r.setItemCode(itemCode);
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
}
