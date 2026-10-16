package com.familyapp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * C-16 一次性调度原子消化服务（design.md D1 可移植写法）。
 *
 * <p>语义：SELECT 今日最旧未执行候选 id → 条件 UPDATE 抢占（受影响行数==1 即成功）→ 读回该行。
 * 并发下仅一个事务能置位；候选被抢则重选下一条，最多重试 8 次防活锁。</p>
 */
@Service
public class PendingScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PendingScheduleService.class);

    private final TaskScheduleRepository repository;

    public PendingScheduleService(TaskScheduleRepository repository) {
        this.repository = repository;
    }

    /** 原子领取一条今日到期调度；无候选或竞争耗尽时返回 empty。 */
    @Transactional
    public Optional<TaskSchedule> claimNext(Long familyId) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Optional<Long> candidate = repository.findNextPendingId(familyId);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            if (repository.claimById(candidate.get()) == 1) {
                log.info("一次性调度[{}]已原子消化", candidate.get());
                return repository.findById(candidate.get());
            }
            // 该候选刚被并发方抢走：循环取下一条
        }
        log.warn("调度抢占连续 8 次竞争失败，本轮放弃");
        return Optional.empty();
    }

    /** 跨家庭先到先得领取一条今日到期调度（3:00 一次性调度用）。 */
    @Transactional
    public Optional<TaskSchedule> claimNextGlobal() {
        for (int attempt = 0; attempt < 8; attempt++) {
            Optional<Long> candidate = repository.findNextPendingIdGlobal();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            if (repository.claimById(candidate.get()) == 1) {
                return repository.findById(candidate.get());
            }
        }
        return Optional.empty();
    }
}
