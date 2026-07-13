package com.family.common.context;

/**
 * 用户上下文工具类
 * 使用 ThreadLocal 存储当前请求的用户信息
 */
public class UserContext {

    /**
     * 用户ID
     */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    /**
     * 家庭ID
     */
    private static final ThreadLocal<Long> FAMILY_ID = new ThreadLocal<>();

    /**
     * 角色
     */
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /**
     * 获取用户ID
     *
     * @return 用户ID
     */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /**
     * 设置用户ID
     *
     * @param userId 用户ID
     */
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取家庭ID
     *
     * @return 家庭ID
     */
    public static Long getFamilyId() {
        return FAMILY_ID.get();
    }

    /**
     * 设置家庭ID
     *
     * @param familyId 家庭ID
     */
    public static void setFamilyId(Long familyId) {
        FAMILY_ID.set(familyId);
    }

    /**
     * 获取角色
     *
     * @return 角色
     */
    public static String getRole() {
        return ROLE.get();
    }

    /**
     * 设置角色
     *
     * @param role 角色
     */
    public static void setRole(String role) {
        ROLE.set(role);
    }

    /**
     * 清除所有用户信息
     */
    public static void clear() {
        USER_ID.remove();
        FAMILY_ID.remove();
        ROLE.remove();
    }

}
