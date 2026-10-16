package com.familyapp.category.dto;

import jakarta.validation.constraints.Size;

public class UpdateCategoryRequest {

    @Size(max = 50, message = "name exceeds 50 chars")
    private String name;

    @Size(max = 20, message = "color exceeds 20 chars")
    private String color;

    @Size(max = 50, message = "icon exceeds 50 chars")
    private String icon;

    private Integer sortOrder;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}