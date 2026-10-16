package com.familyapp.family;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.InviteCreateRequest;
import com.familyapp.family.dto.InviteResponse;
import com.familyapp.family.dto.TransferOwnerRequest;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立连接和真实事务，验证家庭锁等待后读取的是已提交的新状态。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class FamilyConcurrencyIntegrationTest {
    @Autowired FamilyService families;
    @Autowired InviteService invites;
    @Autowired FamilyRepository familyRepository;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.familyapp.profile.PersonProfileService profiles;

    Long ownerId;
    Long firstUser;
    Long secondUser;
    Long familyId;
    InviteResponse invite;

    @BeforeEach
    void setUp() {
        ownerId = newUser();
        firstUser = newUser();
        secondUser = newUser();
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("并发测试家庭");
        familyId = families.createFamily(req, ownerId).id();
        asOwner(() -> invite = invites.createInvite(familyId, new InviteCreateRequest()));
    }

    @Test
    void joiningWithExistingNicknameUsesNextAvailableName() {
        String nickname = users.findById(ownerId).orElseThrow().getNickname();
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, firstUser);
        createVirtualProfile(nickname + "（2）");
        invites.acceptInviteForUser(invite.code(), firstUser);
        assertEquals(nickname + "（3）", profileName(firstUser));
        assertEquals(nickname, users.findById(firstUser).orElseThrow().getNickname());
        assertEquals(nickname, profileName(ownerId));
        assertEquals(3, count("person_profiles"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM person_profiles WHERE family_id = ? AND name = ? AND user_id IS NULL",
                Integer.class, familyId, nickname + "（2）"));
    }

    @Test
    void longUnicodeNicknameRemainsWithinNameLimit() {
        String nickname = "家".repeat(46) + "😀" + "人".repeat(2);
        createVirtualProfile(nickname);
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, firstUser);
        invites.acceptInviteForUser(invite.code(), firstUser);
        String actual = profileName(firstUser);
        assertEquals("家".repeat(46) + "（2）", actual);
        assertTrue(actual.length() <= 50);
    }

    @Test
    void concurrentSameNicknamesGetDistinctProfiles() throws Exception {
        InviteCreateRequest req = new InviteCreateRequest();
        req.setMaxUses(2);
        asOwner(() -> invite = invites.createInvite(familyId, req));
        jdbc.update("UPDATE users SET nickname = '同名家人' WHERE id IN (?, ?)", firstUser, secondUser);
        assertNull(interleave(() -> invites.acceptInviteForUser(invite.code(), firstUser),
                () -> invites.acceptInviteForUser(invite.code(), secondUser)));
        assertEquals("同名家人", profileName(firstUser));
        assertEquals("同名家人（2）", profileName(secondUser));
        assertEquals(3, count("person_profiles"));
    }

    @Test
    void manualProfileCreationBeforeJoiningReservesName() throws Exception {
        jdbc.update("UPDATE users SET nickname = '新家人' WHERE id = ?", firstUser);
        assertNull(interleave(() -> createVirtualProfile("新家人"),
                () -> invites.acceptInviteForUser(invite.code(), firstUser)));
        assertEquals("新家人（2）", profileName(firstUser));
    }

    @Test
    void rejoiningKeepsExistingProfileIdentityAndName() {
        invites.acceptInviteForUser(invite.code(), firstUser);
        Long profileId = jdbc.queryForObject("SELECT id FROM person_profiles WHERE family_id = ? AND user_id = ?",
                Long.class, familyId, firstUser);
        String name = profileName(firstUser);
        UserContext.setCurrent(new UserContext.CurrentUser(firstUser, null, name, familyId, "ADULT"));
        try { families.leaveFamily(familyId); } finally { UserContext.clear(); }
        jdbc.update("UPDATE users SET nickname = '改过昵称' WHERE id = ?", firstUser);
        asOwner(() -> invite = invites.createInvite(familyId, new InviteCreateRequest()));
        invites.acceptInviteForUser(invite.code(), firstUser);
        assertEquals(profileId, jdbc.queryForObject("SELECT id FROM person_profiles WHERE family_id = ? AND user_id = ?",
                Long.class, familyId, firstUser));
        assertEquals(name, profileName(firstUser));
        assertEquals(2, count("person_profiles"));
    }

    @Test
    void formerChildRejoiningPreservesChildRole() {
        invites.acceptInviteForUser(invite.code(), firstUser);
        jdbc.update("UPDATE family_members SET role = ? WHERE family_id = ? AND user_id = ?",
                "CHILD", familyId, firstUser);

        UserContext.setCurrent(new UserContext.CurrentUser(firstUser, null, "孩子", familyId, "CHILD"));
        try {
            families.leaveFamily(familyId);
        } finally {
            UserContext.clear();
        }

        asOwner(() -> invite = invites.createInvite(familyId, new InviteCreateRequest()));
        invites.acceptInviteForUser(invite.code(), firstUser);

        assertEquals("CHILD", jdbc.queryForObject(
                "SELECT role FROM family_members WHERE family_id = ? AND user_id = ? AND left_at IS NULL",
                String.class, familyId, firstUser));
    }

    private String profileName(Long userId) {
        return jdbc.queryForObject("SELECT name FROM person_profiles WHERE family_id = ? AND user_id = ? AND deleted_at IS NULL",
                String.class, familyId, userId);
    }

    private void createVirtualProfile(String name) {
        com.familyapp.profile.dto.CreatePersonProfileRequest req = new com.familyapp.profile.dto.CreatePersonProfileRequest();
        req.setName(name);
        req.setProfileType("ADULT");
        req.setRelation("家人");
        asOwner(() -> profiles.create(req));
    }

    @Test
    void emptyFamilyCleanupPreservesOtherFamilyAndAccount() throws Exception {
        CreateFamilyRequest req = new CreateFamilyRequest();
        req.setName("保留的家庭");
        Long otherFamily = families.createFamily(req, ownerId).id();
        for (Long id : new Long[]{familyId, otherFamily}) {
            jdbc.update("INSERT INTO categories (name, color, sort_order, family_id, created_by, created_at, updated_at) VALUES ('清理回归分类', '#409EFF', 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, ownerId);
            Long category = jdbc.queryForObject("SELECT id FROM categories WHERE family_id = ?", Long.class, id);
            jdbc.update("INSERT INTO tasks (title, status, priority, source, family_id, category_id, created_by, created_at, updated_at) VALUES ('清理回归任务', 'PENDING', 'MEDIUM', 'MANUAL', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, category, ownerId);
            Long task = jdbc.queryForObject("SELECT id FROM tasks WHERE family_id = ?", Long.class, id);
            jdbc.update("INSERT INTO task_notifications (family_id, task_id, status, attempts, available_at, created_at) VALUES (?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, task);
            jdbc.update("INSERT INTO wechat_inbound_message (msg_id, openid, content, family_id, user_id, status, attempts, received_at, created_at, updated_at) VALUES (?, 'local-test', '清理回归', ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), id, ownerId);
        }
        boolean postgres;
        try (var connection = jdbc.getDataSource().getConnection()) {
            postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        }
        // H2 由实体建表，没有迁移定义的级联外键；级联断言在真实 PostgreSQL 上执行。
        if (postgres) {
            jdbc.update("INSERT INTO subtasks (family_id, parent_task_id, title) SELECT family_id, id, '子任务' FROM tasks WHERE family_id = ?", familyId);
        }
        asOwner(() -> families.leaveFamily(familyId));
        for (String table : new String[]{"categories", "tasks", "task_notifications", "wechat_inbound_message", "family_members", "person_profiles"}) {
            assertEquals(0, count(table), table);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE family_id = ?", Integer.class, otherFamily), table);
        }
        if (postgres) {
            assertEquals(0, count("invites"));
            assertEquals(0, count("subtasks"));
        }
        assertFalse(familyRepository.existsById(familyId));
        assertTrue(familyRepository.existsById(otherFamily));
        assertTrue(users.existsById(ownerId));
    }

    @Test
    void singleUseInviteCannotBeConsumedTwice() throws Exception {
        ResultCode outcome = interleave(
                () -> invites.acceptInviteForUser(invite.code(), firstUser),
                () -> invites.acceptInviteForUser(invite.code(), secondUser));
        assertEquals(ResultCode.INVITE_NOT_FOUND, outcome);
        assertEquals(2, count("family_members"));
        assertEquals(2, count("person_profiles"));
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM invites WHERE family_id = ? AND id = ?",
                Integer.class, familyId, invite.id()));
    }

    @Test
    void joiningFirstPreventsEmptyFamilyDeletion() throws Exception {
        ResultCode outcome = interleave(
                () -> invites.acceptInviteForUser(invite.code(), firstUser),
                () -> asOwner(() -> families.leaveFamily(familyId)));
        assertEquals(ResultCode.OWNER_CANNOT_LEAVE, outcome);
        assertTrue(familyRepository.existsById(familyId));
        assertEquals(2, count("family_members"));
    }

    @Test
    void leavingFirstPreventsJoiningDeletedFamily() throws Exception {
        ResultCode outcome = interleave(
                () -> asOwner(() -> families.leaveFamily(familyId)),
                () -> invites.acceptInviteForUser(invite.code(), firstUser));
        assertEquals(ResultCode.FAMILY_NOT_FOUND, outcome);
        assertFalse(familyRepository.existsById(familyId));
        assertEquals(0, count("family_members"));
        assertEquals(0, count("person_profiles"));
    }

    @Test
    void cancellationFirstPreventsInviteConsumption() throws Exception {
        ResultCode outcome = interleave(
                () -> asOwner(() -> invites.cancelInvite(familyId, invite.id())),
                () -> invites.acceptInviteForUser(invite.code(), firstUser));
        assertEquals(ResultCode.INVITE_NOT_FOUND, outcome);
        assertEquals(1, count("family_members"));
    }

    @Test
    void formerOwnerCanLeaveAfterConcurrentTransfer() throws Exception {
        invites.acceptInviteForUser(invite.code(), firstUser);
        TransferOwnerRequest req = new TransferOwnerRequest();
        req.setTargetUserId(firstUser);
        ResultCode outcome = interleave(
                () -> asOwner(() -> families.transferOwner(familyId, req)),
                () -> asOwner(() -> families.leaveFamily(familyId)));
        assertNull(outcome);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM family_members WHERE family_id = ? AND left_at IS NULL AND role = 'OWNER' AND user_id = ?",
                Integer.class, familyId, firstUser));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM family_members WHERE family_id = ? AND left_at IS NULL AND user_id = ?",
                Integer.class, familyId, ownerId));
    }

    private ResultCode interleave(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                familyRepository.findActiveByIdForUpdate(familyId).orElseThrow();
                locked.countDown();
                await(release);
                first.run();
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<ResultCode> waiter = pool.submit(() -> {
                started.countDown();
                try {
                    second.run();
                    return null;
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // 第二个事务必须等待家庭锁，不能先读权限、次数后绕过锁提交。
            assertThrows(TimeoutException.class, () -> waiter.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            return waiter.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void asOwner(Runnable action) {
        UserContext.setCurrent(new UserContext.CurrentUser(ownerId, null, "管理员", familyId, "OWNER"));
        try { action.run(); } finally { UserContext.clear(); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("等待事务释放超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Long newUser() {
        User user = new User();
        user.setNickname(UUID.randomUUID().toString());
        user.setEmail(user.getNickname() + "@example.test");
        user.setPasswordHash("测试占位哈希");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return users.save(user).getId();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE family_id = ?", Integer.class, familyId);
    }
}
