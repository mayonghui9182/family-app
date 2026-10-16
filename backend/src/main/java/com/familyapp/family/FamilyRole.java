package com.familyapp.family;

/**
 * 家庭成员角色。严格对应 V4 DDL family_members.role CHECK IN (OWNER,ADULT,CHILD)。
 * 数据库存字符串（不存 ordinal），保持可扩展性和可读性。
 */
public enum FamilyRole {
    /** 家庭管理员（创建者）：拥有最高权限，可转让身份、踢人、解散家庭。 */
    OWNER,
    /** 成人成员：可创建/编辑任务、分类、邀请成员。 */
    ADULT,
    /** 孩子成员：只读为主，仅能操作自己创建或分配给自己的任务。 */
    CHILD;

    /**
     * 返回中文角色名（用于登录响应 roleName 字段，适配老人显示）。
     */
    public String getChineseName() {
        return switch (this) {
            case OWNER -> "家庭管理员";
            case ADULT -> "成人成员";
            case CHILD -> "孩子成员";
        };
    }
}