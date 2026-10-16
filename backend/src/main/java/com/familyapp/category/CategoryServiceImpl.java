package com.familyapp.category;

import com.familyapp.auth.UserContext;
import com.familyapp.category.dto.CategoryResponse;
import com.familyapp.category.dto.CreateCategoryRequest;
import com.familyapp.category.dto.MergeRequest;
import com.familyapp.category.dto.UpdateCategoryRequest;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.FamilyRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private static final String DEFAULT_COLOR = "#909399";
    private static final int DEFAULT_SORT_ORDER = 0;

    private final CategoryRepository repo;

    public CategoryServiceImpl(CategoryRepository repo) { this.repo = repo; }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return repo.findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(getCurrentFamilyId())
            .stream()
            .map(CategoryResponse::fromEntity)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse get(Long id) {
        return CategoryResponse.fromEntity(findOrFail(id));
    }

    @Override
    public CategoryResponse create(CreateCategoryRequest req) {
        assertCanManageCategory();
        Long familyId = getCurrentFamilyId();
        if (repo.existsByFamilyIdAndNameAndDeletedAtIsNull(familyId, req.getName())) {
            throw new ApiException(ResultCode.CATEGORY_NAME_DUPLICATED,
                "category name '" + req.getName() + "' already exists in family " + familyId);
        }
        Category c = new Category();
        c.setName(req.getName());
        c.setColor(req.getColor() != null ? req.getColor() : DEFAULT_COLOR);
        c.setIcon(req.getIcon());
        c.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : DEFAULT_SORT_ORDER);
        c.setFamilyId(familyId);
        c.setCreatedBy(UserContext.current().userId());
        try {
            return CategoryResponse.fromEntity(repo.save(c));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ResultCode.CATEGORY_NAME_DUPLICATED,
                "category name '" + req.getName() + "' already exists (concurrent)");
        }
    }

    @Override
    public CategoryResponse update(Long id, UpdateCategoryRequest req) {
        assertCanManageCategory();
        Category c = findOrFail(id);
        if (req.getName() != null && !req.getName().equals(c.getName())) {
            if (repo.existsByFamilyIdAndNameAndDeletedAtIsNull(c.getFamilyId(), req.getName())) {
                throw new ApiException(ResultCode.CATEGORY_NAME_DUPLICATED,
                    "category name '" + req.getName() + "' already exists");
            }
            c.setName(req.getName());
        }
        if (req.getColor() != null) c.setColor(req.getColor());
        if (req.getIcon() != null) c.setIcon(req.getIcon());
        if (req.getSortOrder() != null) c.setSortOrder(req.getSortOrder());
        return CategoryResponse.fromEntity(repo.save(c));
    }

    @Override
    public void delete(Long id, Long mergeTo) {
        assertCanManageCategory();
        Category c = findOrFail(id);
        long taskCount = repo.countTasksByCategoryId(id);
        if (taskCount == 0) {
            c.setDeletedAt(Instant.now());
            repo.save(c);
            return;
        }
        if (mergeTo == null) {
            throw new ApiException(ResultCode.CATEGORY_MERGE_TARGET_INVALID,
                "category has " + taskCount + " task(s); mergeTo is required to re-point them before delete");
        }
        MergeRequest req = new MergeRequest();
        req.setMergeTo(mergeTo);
        merge(id, req);
    }

    @Override
    public void merge(Long sourceId, MergeRequest req) {
        assertCanManageCategory();
        Long familyId = getCurrentFamilyId();
        if (sourceId.equals(req.getMergeTo())) {
            throw new ApiException(ResultCode.CATEGORY_MERGE_TARGET_INVALID,
                "cannot merge a category into itself");
        }
        Category source = repo.findByIdAndFamilyIdAndDeletedAtIsNull(sourceId, familyId)
            .orElseThrow(() -> new ApiException(ResultCode.CATEGORY_NOT_FOUND,
                "source category " + sourceId + " not found"));
        Category target = repo.findByIdAndFamilyIdAndDeletedAtIsNull(req.getMergeTo(), familyId)
            .orElseThrow(() -> new ApiException(ResultCode.CATEGORY_MERGE_TARGET_INVALID,
                "merge target category " + req.getMergeTo() + " not found or deleted"));
        if (!source.getFamilyId().equals(target.getFamilyId())) {
            throw new ApiException(ResultCode.CATEGORY_MERGE_TARGET_INVALID,
                "source and target categories must belong to the same family");
        }
        repo.reassignTasks(sourceId, req.getMergeTo());
        source.setDeletedAt(Instant.now());
        repo.save(source);
    }

    private Category findOrFail(Long id) {
        return repo.findByIdAndFamilyIdAndDeletedAtIsNull(id, getCurrentFamilyId())
            .orElseThrow(() -> new ApiException(ResultCode.CATEGORY_NOT_FOUND,
                "category " + id + " not found"));
    }

    private Long getCurrentFamilyId() {
        Long familyId = UserContext.current().familyId();
        if (familyId == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED,
                "request is missing family context (X-Family-Id)");
        }
        return familyId;
    }

    private void assertCanManageCategory() {
        String role = UserContext.current().role();
        if (FamilyRole.CHILD.name().equals(role)) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION,
                "您的角色不能管理分类，请让家长帮忙操作");
        }
    }
}