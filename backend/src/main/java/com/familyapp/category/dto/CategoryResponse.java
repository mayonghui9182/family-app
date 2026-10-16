package com.familyapp.category.dto;

import com.familyapp.category.Category;

import java.time.Instant;

public record CategoryResponse(
    Long id,
    String name,
    String color,
    String icon,
    Integer sortOrder,
    Long familyId,
    Instant createdAt,
    Instant updatedAt
) {
    public static CategoryResponse fromEntity(Category c) {
        return new CategoryResponse(
            c.getId(), c.getName(), c.getColor(), c.getIcon(),
            c.getSortOrder(), c.getFamilyId(),
            c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}