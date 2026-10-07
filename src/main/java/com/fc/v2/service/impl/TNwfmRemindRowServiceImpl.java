package com.fc.v2.service.impl;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmRemindRowMapper;
import com.fc.v2.model.auto.TNwfmRemindRow;
import com.fc.v2.service.ITNwfmRemindRowService;

/**
 * 到期提醒条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmRemindRowServiceImpl implements ITNwfmRemindRowService {

    /** 提前提醒执行窗口：到期前 5 天起可发，到期前 2 天截止 */
    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TNwfmRemindRowMapper nwfmRemindRowMapper;

    @Override
    public TNwfmRemindRow selectTNwfmRemindRowById(Long id) {
        if (id == null) {
            return null;
        }
        return this.nwfmRemindRowMapper.selectById(id);
    }

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static Date shift(Date d, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    @Override
    public List<TNwfmRemindRow> listDue(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TNwfmRemindRow>();
        }
        String winStart = ts(shift(at, WIN_FROM));
        String winEnd = ts(shift(at, WIN_TO));
        String now = ts(at);
        List<TNwfmRemindRow> all = this.nwfmRemindRowMapper.selectList(new QueryWrapper<TNwfmRemindRow>()
                .eq("del_flag", 0).eq("status", STATUS_WAIT));
        List<TNwfmRemindRow> due = new java.util.ArrayList<TNwfmRemindRow>();
        for (TNwfmRemindRow r : all) {
            if (r.getDueAt() == null) {
                continue;
            }
            String dueAt = ts(r.getDueAt());
            // 执行窗口内（到期前 2~5 天，边界含等号），或已到/过到期时刻仍未发
            boolean inWindow = dueAt.compareTo(winStart) >= 0 && dueAt.compareTo(winEnd) <= 0;
            boolean overdue = dueAt.compareTo(now) <= 0;
            if (inWindow || overdue) {
                due.add(r);
            }
        }
        return due;
    }

    /**
     * 执行一次：处理一条落一条（status=1 已发），单条失败跳过继续，
     * 返回**成功条数**。一条都没有就回 0——空批次也是一句正常回话，不许撂挑子。
     */
    @Override
    public int runOnce(Date at) {
        List<TNwfmRemindRow> due = listDue(at);
        if (due.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (TNwfmRemindRow r : due) {
            try {
                if (r.getAmount() == null || r.getAmount().signum() < 0) {
                    r.setStatus(Integer.valueOf(STATUS_FAIL));
                } else {
                    r.setStatus(Integer.valueOf(STATUS_DONE));
                    ok++;
                }
                this.nwfmRemindRowMapper.updateById(r);
            } catch (RuntimeException ignore) {
                // 单条失败不影响本批其余条目
            }
        }
        return ok;
    }
}
