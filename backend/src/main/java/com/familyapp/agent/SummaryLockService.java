package com.familyapp.agent;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Change 5 分布式锁服务（C-19 抢锁 / C-20 收尾）。
 * 锁实现 = INSERT status='running' 占位，依赖 uk_sel_family_run_date
 * （Change 1 afterMigrate 表达式唯一索引，键语义为 UTC 日历日）互斥；
 * 命中唯一冲突 → 视为当日已跑、skip（不先 SELECT 再 INSERT，无竞争窗口）。
 * force=true：先删除同 UTC 日旧行再重插（手动强制重跑语义）。
 */
@Service
public class SummaryLockService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SummaryExecutionLogRepository summaryLogRepository;

    public SummaryLockService(SummaryExecutionLogRepository summaryLogRepository) {
        this.summaryLogRepository = summaryLogRepository;
    }

    /** 当前 UTC 日历日窗口 [start, end)。 */
    public static Instant[] utcDayWindow() {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        Instant start = todayUtc.atStartOfDay().toInstant(ZoneOffset.UTC);
        return new Instant[] {start, start.plus(1, ChronoUnit.DAYS)};
    }

    /**
     * 抢锁：INSERT status='running'。唯一冲突由调用方捕获
     * DataIntegrityViolationException 判为未抢到。
     */
    @Transactional
    public SummaryExecutionLog claim(Long familyId, String triggerSource, boolean force) {
        Instant[] window = utcDayWindow();
        if (force) {
            List<SummaryExecutionLog> sameDay = summaryLogRepository
                    .findByFamilyIdAndRunAtGreaterThanEqualAndRunAtLessThan(
                            familyId, window[0], window[1]);
            summaryLogRepository.deleteAll(sameDay);
            summaryLogRepository.flush();
        }
        SummaryExecutionLog row = new SummaryExecutionLog();
        row.setFamilyId(familyId);
        row.setStatus("running");
        row.setTriggerSource(triggerSource == null ? "auto" : triggerSource);
        return summaryLogRepository.saveAndFlush(row);
    }

    /** 当日已有执行记录则返回首条（冲突响应附带），否则 null。 */
    public SummaryExecutionLog findSameDay(Long familyId) {
        Instant[] window = utcDayWindow();
        List<SummaryExecutionLog> rows = summaryLogRepository
                .findByFamilyIdAndRunAtGreaterThanEqualAndRunAtLessThan(
                        familyId, window[0], window[1]);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 收尾：running → success/failed；行不属于该家庭按 40407 拒绝（防枚举）。 */
    @Transactional
    public void finish(Long familyId, Long logId, AgentApiDtos.SummaryLogFinishReq req) {
        if (!"success".equals(req.status()) && !"failed".equals(req.status())) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        SummaryExecutionLog row = summaryLogRepository.findById(logId)
                .filter(r -> r.getFamilyId().equals(familyId))
                .orElseThrow(() -> new ApiException(ResultCode.SUMMARY_LOG_NOT_FOUND));
        if (!"running".equals(row.getStatus())) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION);
        }
        row.setStatus(req.status());
        row.setCategoriesUpdated(MAPPER.valueToTree(
                req.categoriesUpdated() == null ? List.of() : req.categoriesUpdated()));
        if (req.failedCategories() != null && !req.failedCategories().isEmpty()) {
            StringBuilder reasons = new StringBuilder();
            for (var f : req.failedCategories()) {
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append("分类 ").append(f.categoryId()).append("：")
                        .append(f.reason() == null ? "未知原因" : f.reason());
            }
            row.setErrorMessage(reasons.toString());
        }
        summaryLogRepository.save(row);
    }
}
