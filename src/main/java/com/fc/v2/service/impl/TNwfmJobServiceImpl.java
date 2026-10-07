package com.fc.v2.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmJobMapper;
import com.fc.v2.mapper.auto.TNwfmJobRowMapper;
import com.fc.v2.model.auto.TNwfmJob;
import com.fc.v2.model.auto.TNwfmJobRow;
import com.fc.v2.service.ITNwfmJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 器具核验作业 Service实现（job-lifecycle 形状：批量作业提交/生效/回滚，跨模块协调）
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TNwfmJobServiceImpl implements ITNwfmJobService {

    @Autowired
    private TNwfmJobMapper nwfmJobMapper;

    @Autowired
    private TNwfmJobRowMapper nwfmJobRowMapper;

    @Override
    public TNwfmJob selectTNwfmJobById(Long id) {
        if (id == null) {
            return null;
        }
        return this.nwfmJobMapper.selectById(id);
    }

    @Override
    public TNwfmJob selectByJobNo(String jobNo) {
        if (jobNo == null) {
            return null;
        }
        return this.nwfmJobMapper.selectOne(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    /**
     * 提交一批作业行：
     * - 同一作业号重复提交幂等，直接返回原作业号，数量不再翻一倍；
     * - 先把所有行验完再落库：一行对不上，表头和其余行整批都不落（一条脏数据不留）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitJob(String jobNo, List<TNwfmJob> rows) {
        TNwfmJob existed = selectByJobNo(jobNo);
        if (existed != null) {
            // 同一批码只交过一回：再交一次不再插一遍
            return jobNo;
        }

        int total = rows == null ? 0 : rows.size();
        // 先验后落：行号按批内连续顺序点，报错报的就是单据上对得上的行次
        for (int i = 0; i < total; i++) {
            TNwfmJob r = rows.get(i);
            if (r.getDeviceNo() == null || r.getDeviceNo().trim().isEmpty()
                    || r.getAmount() == null || r.getJobQty() == null) {
                throw new IllegalArgumentException("行数据不完整: " + (i + 1));
            }
        }

        TNwfmJob job = new TNwfmJob();
        job.setJobNo(jobNo);
        job.setStatus(Integer.valueOf(ST_PENDING));
        job.setRowNo(Integer.valueOf(0));
        job.setTotalRows(Integer.valueOf(total));
        job.setDoneRows(Integer.valueOf(0));
        job.setFailRows(Integer.valueOf(0));
        job.setDelFlag(Integer.valueOf(0));
        this.nwfmJobMapper.insert(job);

        for (int i = 0; i < total; i++) {
            TNwfmJob r = rows.get(i);
            r.setId(null);
            r.setJobNo(jobNo);
            r.setRowNo(Integer.valueOf(i + 1));
            r.setStatus(Integer.valueOf(ST_DRAFT));
            r.setDelFlag(Integer.valueOf(0));
            this.nwfmJobMapper.insert(r);
        }
        return jobNo;
    }

    /**
     * 推进到生效：
     * - 终态守卫：已生效的重复推进幂等返回，已回滚的不允许再生效；
     * - 版本锁：撤下/未生效版本的行不写回，计失败；
     * - 写回守恒：业务主表（t_nwfm_job 行）与流水（t_nwfm_job_row）一起落；
     * - 单行失败隔离：一行出错不炸整批，失败行计数，整单按结果落"已生效/部分生效"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int effectJob(String jobNo) {
        TNwfmJob header = getHeader(jobNo);
        if (header == null) {
            return 0;
        }
        int st = header.getStatus() == null ? ST_DRAFT : header.getStatus().intValue();
        if (st == ST_EFFECTED) {
            // 已生效重复推进：原数返回，不重复写回
            return header.getDoneRows() == null ? 0 : header.getDoneRows().intValue();
        }
        if (st == ST_ROLLED || st == ST_ROLLING) {
            throw new IllegalStateException("作业已回滚，不能再生效: " + jobNo);
        }

        header.setStatus(Integer.valueOf(ST_EFFECTING));
        if (header.getEffStart() == null) {
            header.setEffStart(new java.util.Date());
        }
        this.nwfmJobMapper.updateById(header);

        List<TNwfmJob> items = listDetails(jobNo);
        int done = 0;
        int fail = 0;
        for (TNwfmJob r : items) {
            int rowStatus = r.getStatus() == null ? ST_DRAFT : r.getStatus().intValue();
            if (rowStatus == ST_EFFECTED && Integer.valueOf(1).equals(r.getWritebackFlag())) {
                // 部分生效后补推：已写回的行不重复落
                done++;
                continue;
            }
            if (!Integer.valueOf(1).equals(r.getVersionStatus())) {
                // 版本锁：撤下/过期/未生效版本不写回
                r.setStatus(Integer.valueOf(ST_DRAFT));
                r.setFailReason("判据版本未生效或已撤下，不予写回");
                this.nwfmJobMapper.updateById(r);
                fail++;
                continue;
            }
            try {
                r.setWritebackFlag(Integer.valueOf(1));
                r.setStatus(Integer.valueOf(ST_EFFECTED));
                r.setFailReason(null);
                this.nwfmJobMapper.updateById(r);
                // 流水/留痕同步落，主表与流水守恒
                upsertEffectFlow(r);
                done++;
            } catch (Exception e) {
                // 一行失败不连累整批
                r.setFailReason("写回失败: " + e.getMessage());
                this.nwfmJobMapper.updateById(r);
                fail++;
            }
        }

        header.setTotalRows(Integer.valueOf(items.size()));
        header.setDoneRows(Integer.valueOf(done));
        header.setFailRows(Integer.valueOf(fail));
        header.setStatus(Integer.valueOf(fail == 0 ? ST_EFFECTED : ST_PARTIAL));
        this.nwfmJobMapper.updateById(header);
        return done;
    }

    /**
     * 回滚已生效作业：
     * - 终态守卫：已回滚的再调一次幂等返回，补偿流水不重复写；未生效的作业不许回滚；
     * - 每行只补偿一次（compensated 去重），补偿次数不随回滚次数累加。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackJob(String jobNo) {
        TNwfmJob header = getHeader(jobNo);
        if (header == null) {
            return 0;
        }
        int st = header.getStatus() == null ? ST_DRAFT : header.getStatus().intValue();
        List<TNwfmJob> items = listDetails(jobNo);
        if (st == ST_ROLLED) {
            // 终态幂等：不重复写补偿流水，原数返回
            return items.size();
        }
        if (st != ST_EFFECTED && st != ST_PARTIAL) {
            throw new IllegalStateException("作业尚未生效，不能回滚: " + jobNo);
        }

        header.setStatus(Integer.valueOf(ST_ROLLING));
        this.nwfmJobMapper.updateById(header);

        int back = 0;
        for (TNwfmJob r : items) {
            if (!Integer.valueOf(1).equals(r.getCompensated())) {
                // 补偿去重：同一行回滚几次都只记一笔
                int cc = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
                r.setCompensateCnt(Integer.valueOf(cc + 1));
                r.setCompensateFlag(Integer.valueOf(1));
                r.setCompensated(Integer.valueOf(1));
                upsertCompensateFlow(r);
            }
            r.setStatus(Integer.valueOf(ST_ROLLED));
            this.nwfmJobMapper.updateById(r);
            back++;
        }
        header.setStatus(Integer.valueOf(ST_ROLLED));
        this.nwfmJobMapper.updateById(header);
        return back;
    }

    /** 作业表头（row_no=0），不带行，避免多行时 selectOne 炸 TooManyResults */
    private TNwfmJob getHeader(String jobNo) {
        return this.nwfmJobMapper.selectOne(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    /** 作业明细行（row_no>0），表头不参与逐行写回/补偿 */
    private List<TNwfmJob> listDetails(String jobNo) {
        return this.nwfmJobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).ne("row_no", 0).eq("del_flag", 0)
                .orderByAsc("row_no"));
    }

    /** 生效流水：主表写回一笔，流水同步一笔（同作业同行次只留一条） */
    private void upsertEffectFlow(TNwfmJob r) {
        TNwfmJobRow flow = this.nwfmJobRowMapper.selectOne(new QueryWrapper<TNwfmJobRow>()
                .eq("job_no", r.getJobNo()).eq("row_no", r.getRowNo())
                .eq("del_flag", 0).last("limit 1"));
        boolean isNew = (flow == null);
        if (isNew) {
            flow = new TNwfmJobRow();
            flow.setJobNo(r.getJobNo());
            flow.setRowNo(r.getRowNo());
            flow.setDelFlag(Integer.valueOf(0));
        }
        flow.setDeviceNo(r.getDeviceNo());
        flow.setAmount(r.getAmount());
        flow.setJobQty(r.getJobQty());
        flow.setVersion(r.getVersion());
        flow.setVersionStatus(r.getVersionStatus());
        flow.setWritebackFlag(Integer.valueOf(1));
        flow.setStatus(r.getStatus());
        if (isNew) {
            this.nwfmJobRowMapper.insert(flow);
        } else {
            this.nwfmJobRowMapper.updateById(flow);
        }
    }

    /** 补偿流水：在生效流水上补记补偿标记，同一行只补一笔 */
    private void upsertCompensateFlow(TNwfmJob r) {
        TNwfmJobRow flow = this.nwfmJobRowMapper.selectOne(new QueryWrapper<TNwfmJobRow>()
                .eq("job_no", r.getJobNo()).eq("row_no", r.getRowNo())
                .eq("del_flag", 0).last("limit 1"));
        if (flow == null) {
            flow = new TNwfmJobRow();
            flow.setJobNo(r.getJobNo());
            flow.setRowNo(r.getRowNo());
            flow.setDeviceNo(r.getDeviceNo());
            flow.setAmount(r.getAmount());
            flow.setJobQty(r.getJobQty());
            flow.setVersion(r.getVersion());
            flow.setVersionStatus(r.getVersionStatus());
            flow.setDelFlag(Integer.valueOf(0));
        }
        flow.setCompensateFlag(Integer.valueOf(1));
        flow.setCompensated(Integer.valueOf(1));
        flow.setStatus(Integer.valueOf(ST_ROLLED));
        if (flow.getId() == null) {
            this.nwfmJobRowMapper.insert(flow);
        } else {
            this.nwfmJobRowMapper.updateById(flow);
        }
    }

    // 作业状态机常量（草稿→待审→生效中→已生效/部分生效→回滚中→已回滚）
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;
}
