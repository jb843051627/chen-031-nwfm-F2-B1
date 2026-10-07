package com.fc.v2.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmJobMapper;
import com.fc.v2.model.auto.TNwfmJob;
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
     * 提交一批作业行。
     * 同一作业编号只收一回：已提交过的作业再次交件，原样返回该作业号，
     * 明细一行不重复落（重试幂等，数量不会翻倍）。
     * 新提交整笔一个事务：任一行不完整整批不落，不会留下半截脏作业。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitJob(String jobNo, List<TNwfmJob> rows) {
        TNwfmJob existed = selectByJobNo(jobNo);
        if (existed != null) {
            return jobNo;
        }
        TNwfmJob job = new TNwfmJob();
        job.setJobNo(jobNo);
        job.setStatus(Integer.valueOf(ST_PENDING));
        job.setRowNo(Integer.valueOf(0));
        job.setTotalRows(Integer.valueOf(rows == null ? 0 : rows.size()));
        job.setDoneRows(Integer.valueOf(0));
        job.setFailRows(Integer.valueOf(0));
        // 先点行数再建表头：行不完整直接抛，事务回滚，不留空壳作业
        if (rows != null) {
            int idx = 0;
            for (TNwfmJob r : rows) {
                idx++;
                if (r.getDeviceNo() == null || r.getAmount() == null) {
                    throw new IllegalArgumentException("行数据不完整: " + idx);
                }
                r.setJobNo(jobNo);
                r.setRowNo(Integer.valueOf(idx));
                r.setStatus(Integer.valueOf(ST_DRAFT));
            }
        }
        this.nwfmJobMapper.insert(job);
        if (rows != null) {
            for (TNwfmJob r : rows) {
                this.nwfmJobMapper.insert(r);
            }
        }
        return jobNo;
    }

    /**
     * 推进到生效。
     * 终态守卫：已生效、已回滚的作业不再推进（再点一回也不会重写一遍）；
     * 部分生效的重交只补落尚未写回的行；单行失败只记本行失败原因，
     * 不回滚整批——全成置「已生效」，有败有成置「部分生效」。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int effectJob(String jobNo) {
        TNwfmJob job0 = selectByJobNo(jobNo);
        if (job0 == null) {
            return 0;
        }
        Integer st = job0.getStatus();
        if (st != null && (st.intValue() == ST_EFFECTED || st.intValue() == ST_ROLLED)) {
            return job0.getDoneRows() == null ? 0 : job0.getDoneRows().intValue();
        }
        job0.setStatus(Integer.valueOf(ST_EFFECTING));
        this.nwfmJobMapper.updateById(job0);

        List<TNwfmJob> items = this.nwfmJobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).gt("row_no", 0).eq("del_flag", 0));
        int done = 0;
        int fail = 0;
        for (TNwfmJob r : items) {
            // 部分生效重交：已写回的行直接认，不重复落
            if (r.getWritebackFlag() != null && r.getWritebackFlag().intValue() == 1) {
                done++;
                continue;
            }
            try {
                r.setWritebackFlag(Integer.valueOf(1));
                r.setStatus(Integer.valueOf(ST_EFFECTED));
                this.nwfmJobMapper.updateById(r);
                done++;
            } catch (RuntimeException ex) {
                // 失败行必须保持"未写回/未生效"，下一批重交还能补落
                r.setWritebackFlag(Integer.valueOf(0));
                r.setStatus(Integer.valueOf(ST_DRAFT));
                r.setFailReason(truncate(ex.getMessage()));
                this.nwfmJobMapper.updateById(r);
                fail++;
            }
        }
        job0.setDoneRows(Integer.valueOf(done));
        job0.setFailRows(Integer.valueOf(fail));
        job0.setStatus(Integer.valueOf(fail > 0 && done > 0 ? ST_PARTIAL
                : fail > 0 ? ST_PENDING : ST_EFFECTED));
        this.nwfmJobMapper.updateById(job0);
        return done;
    }

    /**
     * 回滚已生效作业。
     * 补偿流水一行只写一回：已补偿的行重试不再累加次数；
     * 已回滚的作业再回滚原样返回，不产生第二条补偿（回滚幂等）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackJob(String jobNo) {
        TNwfmJob job1 = selectByJobNo(jobNo);
        if (job1 == null) {
            return 0;
        }
        List<TNwfmJob> items = this.nwfmJobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).gt("row_no", 0).eq("del_flag", 0));
        int back = 0;
        for (TNwfmJob r : items) {
            boolean compensated = (r.getCompensated() != null && r.getCompensated().intValue() == 1)
                    || (r.getCompensateFlag() != null && r.getCompensateFlag().intValue() == 1);
            if (!compensated) {
                int cc = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
                r.setCompensateCnt(Integer.valueOf(cc + 1));
                r.setCompensateFlag(Integer.valueOf(1));
                r.setCompensated(Integer.valueOf(1));
            }
            r.setStatus(Integer.valueOf(ST_ROLLED));
            this.nwfmJobMapper.updateById(r);
            back++;
        }
        job1.setStatus(Integer.valueOf(ST_ROLLED));
        this.nwfmJobMapper.updateById(job1);
        return back;
    }

    // 作业状态机常量（草稿→待审→生效中→已生效/部分生效→回滚中→已回滚）
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    private static java.text.SimpleDateFormat _fmt() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }

    private static String ts(java.util.Date d) {
        return d == null ? "" : _fmt().format(d);
    }

    /** 跨模块写回：基线只落业务主表（流水/留痕缺失 -> 写回不守恒） */
    private void writeback(TNwfmJob r) {
        if (r == null) {
            return;
        }
        r.setWritebackFlag(Integer.valueOf(1));
        this.nwfmJobMapper.updateById(r);
    }

    /** 写补偿流水：单行只写一回，调用侧按 compensated 去重 */
    private void compensate(TNwfmJob r) {
        if (r == null) {
            return;
        }
        int c = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
        r.setCompensateCnt(Integer.valueOf(c + 1));
        r.setCompensateFlag(Integer.valueOf(1));
        this.nwfmJobMapper.updateById(r);
    }
}
