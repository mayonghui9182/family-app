package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** 确认结果与最终任务的家庭作用域关联仓储。 */
public interface WechatConfirmationTaskRepository extends Repository<WechatConfirmationTask, Long> {

    <S extends WechatConfirmationTask> S save(S entity);

    List<WechatConfirmationTask> findByFamilyIdOrderByIdAsc(
            Long familyId, org.springframework.data.domain.Pageable page);

    Optional<WechatConfirmationTask> findByIdAndFamilyId(Long id, Long familyId);

    List<WechatConfirmationTask> findByConfirmationIdAndFamilyIdOrderByCandidateItemIndexAsc(
            Long confirmationId, Long familyId);

    Optional<WechatConfirmationTask> findByTaskIdAndFamilyId(Long taskId, Long familyId);
}

