package com.familyapp.family.dto;

/**
 * 创建邀请码请求。maxUses 1-100 默认 1，ttlDays 1-30 默认 7。
 */
public class InviteCreateRequest {

    /** 最多可被接受次数（1-100，默认 1）。 */
    private Integer maxUses = 1;

    /** 有效天数（1-30，默认 7）。 */
    private Integer ttlDays = 7;

    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public Integer getTtlDays() { return ttlDays; }
    public void setTtlDays(Integer ttlDays) { this.ttlDays = ttlDays; }
}