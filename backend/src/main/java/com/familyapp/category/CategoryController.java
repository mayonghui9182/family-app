package com.familyapp.category;

import com.familyapp.category.dto.CategoryResponse;
import com.familyapp.category.dto.CreateCategoryRequest;
import com.familyapp.category.dto.MergeRequest;
import com.familyapp.category.dto.UpdateCategoryRequest;
import com.familyapp.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) { this.service = service; }

    @GetMapping
    public Result<List<CategoryResponse>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest req) {
        return Result.ok(service.create(req));
    }

    @GetMapping("/{id}")
    public Result<CategoryResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public Result<CategoryResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateCategoryRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id,
                                  @RequestParam(required = false) Long mergeTo) {
        service.delete(id, mergeTo);
        return Result.ok(true);
    }

    @PostMapping("/{id}/merge")
    public Result<Boolean> merge(@PathVariable Long id,
                                 @Valid @RequestBody MergeRequest req) {
        service.merge(id, req);
        return Result.ok(true);
    }
}