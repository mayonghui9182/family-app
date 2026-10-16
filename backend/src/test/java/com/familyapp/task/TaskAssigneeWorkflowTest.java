package com.familyapp.task;

import com.familyapp.auth.UserContext;
import com.familyapp.profile.*;
import com.familyapp.task.dto.*;
import com.familyapp.common.ResultException;
import com.familyapp.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class TaskAssigneeWorkflowTest {
    @Autowired TaskService service;
    @Autowired TaskRepository tasks;
    @Autowired PersonProfileRepository profiles;

    @BeforeEach void context() {
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "爸爸", 1L, "OWNER"));
    }
    @AfterEach void clear() { UserContext.clear(); }

    private PersonProfile profile() {
        PersonProfile p = new PersonProfile();
        p.setFamilyId(1L); p.setUserId(1L); p.setName("爸爸");
        p.setProfileType(ProfileType.ADULT); p.setCreatedBy(1L); p.setAvatarEmoji("👨"); p.setRelation("本人");
        return profiles.saveAndFlush(p);
    }
    private Task create(Long profileId) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("流程验收买菜"); req.setAssigneeProfileId(profileId);
        req.setDueDate(LocalDate.now().minusDays(1));
        return service.createTask(req);
    }
    private TaskFilter mine() {
        return new TaskFilter(null, null, null, null, null, 1L, true, LocalDate.now());
    }

    @Test void 档案指派与历史任务都计入本人列表及统计() {
        PersonProfile p = profile();
        Task task = create(p.getId());
        assertThat(task.getAssigneeId()).isNull();
        Task legacy = new Task(); legacy.setTitle("历史任务"); legacy.setFamilyId(1L);
        legacy.setAssigneeId(1L); legacy.setDueDate(LocalDate.now().minusDays(1));
        tasks.saveAndFlush(legacy);
        var result = service.list(mine());
        assertThat(result).extracting(TaskResponse::id).contains(task.getId(), legacy.getId());
        assertThat(service.stats().mineOverdueCount()).isEqualTo(result.size());
    }

    @Test void 已删除档案不再匹配也不回退残留旧账号() {
        PersonProfile p = profile(); Task task = create(p.getId());
        task.setAssigneeId(1L); tasks.saveAndFlush(task);
        p.setDeletedAt(Instant.now()); profiles.saveAndFlush(p);
        assertThat(service.list(mine())).extracting(TaskResponse::id).doesNotContain(task.getId());
        assertThatThrownBy(() -> create(p.getId())).isInstanceOf(ResultException.class);
    }

    @Test void 其他家庭不能使用该档案或读取该任务() {
        PersonProfile p = profile(); Task task = create(p.getId());
        UserContext.setCurrent(new UserContext.CurrentUser(1L, "test@test.com", "爸爸", 999L, "OWNER"));
        assertThatThrownBy(() -> create(p.getId())).isInstanceOf(ApiException.class);
        assertThat(service.list(mine())).isEmpty();
        assertThatThrownBy(() -> service.get(task.getId())).isInstanceOf(RuntimeException.class);
    }
}
