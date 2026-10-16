package com.familyapp.wechat;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

public interface TaskNotificationRepository extends JpaRepository<TaskNotification, Long> {
    @Query("select n from TaskNotification n where n.familyId = :familyId and n.status in ('PENDING', 'SENDING') "
            + "and n.availableAt <= :now order by n.id")
    List<TaskNotification> pending(@Param("familyId") Long familyId, @Param("now") Instant now, Pageable page);

    /** 原子抢占并设置租约，崩溃后到期可重新领取。 */
    @Modifying
    @Query("update TaskNotification n set n.status = 'SENDING', n.attempts = n.attempts + 1, n.availableAt = :lease "
            + "where n.id = :id and n.familyId = :familyId and n.status in ('PENDING', 'SENDING') and n.availableAt <= :now")
    int claim(@Param("id") Long id, @Param("familyId") Long familyId, @Param("now") Instant now,
              @Param("lease") Instant lease);

    /** 只允许租约持有者结束发送，过期工作者不能覆盖新一次投递。 */
    @Modifying
    @Query("update TaskNotification n set n.status = :status, n.availableAt = :next "
            + "where n.id = :id and n.familyId = :familyId and n.status = 'SENDING' and n.availableAt = :lease")
    int finish(@Param("id") Long id, @Param("familyId") Long familyId, @Param("lease") Instant lease,
               @Param("status") String status, @Param("next") Instant next);
}
