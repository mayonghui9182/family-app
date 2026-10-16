package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 子待办数据访问（C-4 批量创建 / C-6 / C-7 / C-2 嵌套查询）。 */
public interface SubtaskRepository extends JpaRepository<Subtask, Long> {

    /** 按父任务取子待办，sort_order 升序、id 兜底排序稳定。 */
    List<Subtask> findByParentTaskIdOrderBySortOrderAscIdAsc(Long parentTaskId);

    /** family_id 作用域限定查找（内部 API 显式传家庭 ID，不依赖用户上下文）。 */
    Optional<Subtask> findByIdAndFamilyId(Long id, Long familyId);
}
