package com.familyapp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 注意事项数据访问（C-8 新增 / C-9 删除 / C-2 任务详情嵌套）。 */
public interface NoteRepository extends JpaRepository<Note, Long> {

    /** 按多态目标取注意事项，id 升序保证展示顺序稳定。 */
    List<Note> findByTargetTypeAndTargetIdOrderByIdAsc(String targetType, Long targetId);

    /** family_id 作用域限定查找。 */
    Optional<Note> findByIdAndFamilyId(Long id, Long familyId);
}
