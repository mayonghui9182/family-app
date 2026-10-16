package com.familyapp.category;
import com.familyapp.auth.UserContext;
import com.familyapp.auth.UserContext.CurrentUser;
import com.familyapp.category.dto.CategoryResponse;
import com.familyapp.category.dto.CreateCategoryRequest;
import com.familyapp.category.dto.MergeRequest;
import com.familyapp.category.dto.UpdateCategoryRequest;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {
    @Mock CategoryRepository repo;
    @InjectMocks CategoryServiceImpl service;
    private static final Long FAMILY_ID = 1L;
    private Category cat(Long id, String name) {
        Category c = new Category();
        c.setId(id); c.setName(name); c.setFamilyId(FAMILY_ID);
        c.setColor("#909399"); c.setSortOrder(0);
        return c;
    }
    @BeforeEach
    void setUp() { UserContext.setCurrent(new CurrentUser(1L, "test@test.com", "test", FAMILY_ID, "OWNER")); }
    @AfterEach
    void tearDown() { UserContext.clear(); }
    @Test
    void list_empty_returns_empty() {
        when(repo.findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(FAMILY_ID)).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
    }
    @Test
    void list_returns_mapped_and_ordered() {
        Category b = cat(20L, "b"); b.setSortOrder(1);
        Category a = cat(10L, "a"); a.setSortOrder(2);
        when(repo.findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(FAMILY_ID)).thenReturn(List.of(b, a));
        List<CategoryResponse> rs = service.list();
        assertEquals(2, rs.size());
        assertEquals("b", rs.get(0).name());
    }
    @Test
    void create_defaults_color_and_sort_order() {
        when(repo.existsByFamilyIdAndNameAndDeletedAtIsNull(FAMILY_ID, "家务")).thenReturn(false);
        when(repo.save(any(Category.class))).thenAnswer(inv -> { Category c = inv.getArgument(0); c.setId(1L); c.onCreate(); return c; });
        CreateCategoryRequest req = new CreateCategoryRequest(); req.setName("家务");
        CategoryResponse r = service.create(req);
        assertEquals("家务", r.name());
        assertEquals("#909399", r.color());
        assertEquals(0, r.sortOrder());
        assertEquals(FAMILY_ID, r.familyId());
    }
    @Test
    void create_with_full_payload() {
        when(repo.existsByFamilyIdAndNameAndDeletedAtIsNull(FAMILY_ID, "工作")).thenReturn(false);
        when(repo.save(any(Category.class))).thenAnswer(inv -> { Category c = inv.getArgument(0); c.setId(2L); c.onCreate(); return c; });
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("工作"); req.setColor("#409EFF"); req.setIcon("briefcase"); req.setSortOrder(3);
        CategoryResponse r = service.create(req);
        assertEquals("工作", r.name());
        assertEquals("#409EFF", r.color());
        assertEquals("briefcase", r.icon());
        assertEquals(3, r.sortOrder());
    }
    @Test
    void create_rejects_duplicate_name() {
        when(repo.existsByFamilyIdAndNameAndDeletedAtIsNull(FAMILY_ID, "家务")).thenReturn(true);
        CreateCategoryRequest req = new CreateCategoryRequest(); req.setName("家务");
        ApiException ex = assertThrows(ApiException.class, () -> service.create(req));
        assertEquals(ResultCode.CATEGORY_NAME_DUPLICATED, ex.getCode());
    }
    @Test
    void update_partial_color_only() {
        Category c = cat(1L, "家务");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.save(any(Category.class))).thenReturn(c);
        UpdateCategoryRequest req = new UpdateCategoryRequest(); req.setColor("#F56C6C");
        CategoryResponse r = service.update(1L, req);
        assertEquals("#F56C6C", r.color());
        assertEquals("家务", r.name());
    }
    @Test
    void update_rejects_duplicate_name() {
        Category c = cat(1L, "A");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.existsByFamilyIdAndNameAndDeletedAtIsNull(FAMILY_ID, "B")).thenReturn(true);
        UpdateCategoryRequest req = new UpdateCategoryRequest(); req.setName("B");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(1L, req));
        assertEquals(ResultCode.CATEGORY_NAME_DUPLICATED, ex.getCode());
    }
    @Test
    void update_own_name_allowed() {
        Category c = cat(1L, "A");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.save(any(Category.class))).thenReturn(c);
        UpdateCategoryRequest req = new UpdateCategoryRequest(); req.setName("A");
        CategoryResponse r = service.update(1L, req);
        assertEquals("A", r.name());
    }
    @Test
    void update_not_found_throws() {
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(999L, FAMILY_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.update(999L, new UpdateCategoryRequest()));
        assertEquals(ResultCode.CATEGORY_NOT_FOUND, ex.getCode());
    }
    @Test
    void delete_empty_category_succeeds() {
        Category c = cat(1L, "empty");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.countTasksByCategoryId(1L)).thenReturn(0L);
        when(repo.save(any(Category.class))).thenReturn(c);
        service.delete(1L, null);
        assertNotNull(c.getDeletedAt());
        verify(repo).save(c);
    }
    @Test
    void delete_with_tasks_no_mergeTo_rejected() {
        Category c = cat(1L, "has");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.countTasksByCategoryId(1L)).thenReturn(2L);
        ApiException ex = assertThrows(ApiException.class, () -> service.delete(1L, null));
        assertEquals(ResultCode.CATEGORY_MERGE_TARGET_INVALID, ex.getCode());
    }
    @Test
    void delete_self_mergeTo_rejected() {
        Category c = cat(5L, "self");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(5L, FAMILY_ID)).thenReturn(Optional.of(c));
        when(repo.countTasksByCategoryId(5L)).thenReturn(1L);
        ApiException ex = assertThrows(ApiException.class, () -> service.delete(5L, 5L));
        assertEquals(ResultCode.CATEGORY_MERGE_TARGET_INVALID, ex.getCode());
    }
    @Test
    void merge_transfers_tasks_and_soft_deletes_source() {
        Category source = cat(1L, "src");
        Category target = cat(2L, "tgt");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(source));
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(2L, FAMILY_ID)).thenReturn(Optional.of(target));
        when(repo.reassignTasks(1L, 2L)).thenReturn(3);
        when(repo.save(any(Category.class))).thenReturn(source);
        MergeRequest req = new MergeRequest(); req.setMergeTo(2L);
        service.merge(1L, req);
        assertNotNull(source.getDeletedAt());
        verify(repo).reassignTasks(1L, 2L);
    }
    @Test
    void merge_self_rejected() {
        MergeRequest req = new MergeRequest(); req.setMergeTo(5L);
        ApiException ex = assertThrows(ApiException.class, () -> service.merge(5L, req));
        assertEquals(ResultCode.CATEGORY_MERGE_TARGET_INVALID, ex.getCode());
    }
    @Test
    void merge_non_existent_target_rejected() {
        Category source = cat(1L, "src");
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(1L, FAMILY_ID)).thenReturn(Optional.of(source));
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(999L, FAMILY_ID)).thenReturn(Optional.empty());
        MergeRequest req = new MergeRequest(); req.setMergeTo(999L);
        ApiException ex = assertThrows(ApiException.class, () -> service.merge(1L, req));
        assertEquals(ResultCode.CATEGORY_MERGE_TARGET_INVALID, ex.getCode());
    }
    @Test
    void merge_non_existent_source_returns_not_found() {
        when(repo.findByIdAndFamilyIdAndDeletedAtIsNull(999L, FAMILY_ID)).thenReturn(Optional.empty());
        MergeRequest req = new MergeRequest(); req.setMergeTo(1L);
        ApiException ex = assertThrows(ApiException.class, () -> service.merge(999L, req));
        assertEquals(ResultCode.CATEGORY_NOT_FOUND, ex.getCode());
    }
}
