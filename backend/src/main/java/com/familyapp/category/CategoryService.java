package com.familyapp.category;

import com.familyapp.category.dto.CategoryResponse;
import com.familyapp.category.dto.CreateCategoryRequest;
import com.familyapp.category.dto.MergeRequest;
import com.familyapp.category.dto.UpdateCategoryRequest;

import java.util.List;

public interface CategoryService {
    List<CategoryResponse> list();
    CategoryResponse get(Long id);
    CategoryResponse create(CreateCategoryRequest req);
    CategoryResponse update(Long id, UpdateCategoryRequest req);
    void delete(Long id, Long mergeTo);
    void merge(Long sourceId, MergeRequest req);
}