package com.familyapp.wechat.conversation;

import com.familyapp.category.Category;
import com.familyapp.category.CategoryRepository;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.FamilyRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅供精确确认事务使用；调用方已按家庭、绑定、会话顺序持锁。 */
@Service
public class WechatCandidateCategoryResolver {
    private final CategoryRepository categories;
    public WechatCandidateCategoryResolver(CategoryRepository categories) { this.categories = categories; }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> resolve(long familyId, long userId, FamilyRole role,
                                            List<Map<String, Object>> original) {
        Map<String, Category> available = new LinkedHashMap<>();
        for (var category : categories.findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(familyId)) {
            available.putIfAbsent(WechatCategoryNames.key(category.getName()), category);
        }
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (var item : original) {
            var copy = new LinkedHashMap<>(item);
            Object proposed = copy.remove("category_name");
            if (proposed != null) {
                if (!(proposed instanceof String) || copy.get("category_id") != null) {
                    throw new IllegalArgumentException("分类建议名称与ID不符合约束");
                }
                String name = WechatCategoryNames.normalize((String) proposed);
                String key = WechatCategoryNames.key(name);
                Category category = available.get(key);
                if (category == null) {
                    if (role == FamilyRole.CHILD) {
                        throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "请让家长确认创建新的分类");
                    }
                    category = new Category();
                    category.setFamilyId(familyId); category.setCreatedBy(userId); category.setName(name);
                    category = categories.save(category);
                    available.put(key, category);
                }
                copy.put("category_id", category.getId());
            }
            resolved.add(copy);
        }
        return resolved;
    }
}
