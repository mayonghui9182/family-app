package com.familyapp.common;

/**
 * 轻量级业务异常：直接携带 int code + String msg，无需 ResultCode 枚举。
 *
 * <p>用于以下场景：
 * <ul>
 *   <li>ProfileRoleGuard 权限守卫：40303 / 40304 等不适合塞进 ResultCode 枚举的边界码</li>
 *   <li>TaskServiceImpl.createTask 过渡策略冲突：40010「指派给谁参数冲突」</li>
 *   <li>后续 profile/agent 模块新增的模块级业务码（无需全局枚举注册）</li>
 * </ul>
 *
 * <p>与 ApiException 对比：
 * <ul>
 *   <li>ApiException 依赖 ResultCode 枚举 → 需要事先注册，适合全局稳定的错误码（鉴权/任务/分类等）</li>
 *   <li>ResultException 直接传 int+msg → 适合新模块快速迭代、局部边界检查</li>
 * </ul>
 *
 * <p>HTTP 映射：GlobalExceptionHandler 新增 handleResult() 统一转 400（默认）。
 * 需要精确 HTTP 状态码时在 handler 里按 code 段映射（403xx→FORBIDDEN / 404xx→NOT_FOUND / 409xx→CONFLICT）。
 */
public class ResultException extends RuntimeException {

    private final int code;

    public ResultException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
