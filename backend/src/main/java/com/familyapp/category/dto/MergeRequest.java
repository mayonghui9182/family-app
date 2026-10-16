package com.familyapp.category.dto;

import jakarta.validation.constraints.NotNull;

public class MergeRequest {

    @NotNull(message = "mergeTo is required")
    private Long mergeTo;

    public Long getMergeTo() { return mergeTo; }
    public void setMergeTo(Long mergeTo) { this.mergeTo = mergeTo; }
}