package com.familyapp.common;

import org.springframework.http.HttpStatus;

/**
 * 统一业务错误码。code 为 5 位业务码，httpStatus 为对应 HTTP 状态码，msg 为中文提示。
 * <p>所有面向终端用户的消息必须使用中文，适配老人用户群体。</p>
 */
public enum ResultCode {
    DAILY_SOURCE_UNAVAILABLE(50381, HttpStatus.SERVICE_UNAVAILABLE, "工作数据暂不可用，请稍后重试"),
    DAILY_VERSION_CONFLICT(40981, HttpStatus.CONFLICT, "计划已更新，请刷新后重试"),
    DAILY_DISABLED(50382, HttpStatus.SERVICE_UNAVAILABLE, "每日计划功能尚未启用"),
    PASSWORD_RESET_INVALID(40071, HttpStatus.BAD_REQUEST, "链接已失效，请重新申请找回密码"),
    PASSWORD_RESET_UNAVAILABLE(50371, HttpStatus.SERVICE_UNAVAILABLE, "暂时无法找回密码，请稍后再试"),
    PASSWORD_RESET_RATE_LIMITED(42971, HttpStatus.TOO_MANY_REQUESTS, "操作太频繁，请15分钟后再试"),


    // ========== 通用 ==========
    OK(0, HttpStatus.OK, "操作成功"),
    BAD_REQUEST_VALIDATION(40099, HttpStatus.BAD_REQUEST, "参数校验失败"),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),
    SERVER_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试"),

    // ========== 任务相关 ==========
    BAD_REQUEST_TITLE_EMPTY(40001, HttpStatus.BAD_REQUEST, "任务标题不能为空"),
    BAD_REQUEST_TITLE_TOO_LONG(40002, HttpStatus.BAD_REQUEST, "任务标题超过 200 字"),
    BAD_REQUEST_DATE_FORMAT(40003, HttpStatus.BAD_REQUEST, "日期格式不正确"),
    BAD_REQUEST_STATUS_TRANSITION(40011, HttpStatus.BAD_REQUEST, "状态转换不合法"),
    BAD_REQUEST_INVALID_PRIORITY(40012, HttpStatus.BAD_REQUEST, "优先级值不合法"),
    BAD_REQUEST_INVALID_SOURCE(40013, HttpStatus.BAD_REQUEST, "来源值不合法"),
    TASK_NOT_FOUND(40401, HttpStatus.NOT_FOUND, "任务不存在"),
    TASK_INVALID_TRANSITION(40014, HttpStatus.BAD_REQUEST, "任务状态不能这样切换"),

    // ========== 分类相关 ==========
    CATEGORY_NOT_FOUND(40402, HttpStatus.NOT_FOUND, "分类不存在"),
    CATEGORY_NAME_DUPLICATED(40021, HttpStatus.BAD_REQUEST, "分类名称已存在"),
    CATEGORY_MERGE_TARGET_INVALID(40022, HttpStatus.BAD_REQUEST, "合并目标分类不合法"),

    // ========== 鉴权 Token 相关 ==========
    AUTH_HEADER_REQUIRED(40100, HttpStatus.UNAUTHORIZED, "请先登录后再操作"),
    AUTH_TOKEN_INVALID(40101, HttpStatus.UNAUTHORIZED, "登录状态无效，请重新登录"),
    AUTH_TOKEN_EXPIRED(40102, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    UNAUTHORIZED(40103, HttpStatus.UNAUTHORIZED, "未登录或登录已过期，请重新登录"),

    // ========== 注册/登录精确错误码 ==========
    AUTH_EMAIL_NOT_FOUND(40111, HttpStatus.UNAUTHORIZED, "这个邮箱还没注册哦"),
    AUTH_PHONE_NOT_FOUND(40112, HttpStatus.UNAUTHORIZED, "这个手机号还没注册哦"),
    AUTH_EMAIL_WRONG_PASSWORD(40113, HttpStatus.UNAUTHORIZED, "邮箱正确，但密码不对，再试试"),
    AUTH_PHONE_WRONG_PASSWORD(40114, HttpStatus.UNAUTHORIZED, "手机号正确，但密码不对，再试试"),

    // ========== Change 11 微信绑定/登录 ==========
    // 40115 为「未绑定」流程态：HTTP 200 + 业务码，供 wechat-service 分支流转（非 5xx）
    WECHAT_NOT_BOUND(40115, HttpStatus.OK, "微信还没绑定账号"),
    WECHAT_TICKET_INVALID(40116, HttpStatus.BAD_REQUEST, "绑定链接已失效，请从微信公众号菜单重新进入"),

    // ========== 注册参数校验 ==========
    PASSWORD_TOO_SHORT(40031, HttpStatus.BAD_REQUEST, "密码太短了，至少 8 位"),
    PASSWORD_TOO_LONG(40032, HttpStatus.BAD_REQUEST, "密码太长了，最多 64 位"),
    NICKNAME_REQUIRED(40033, HttpStatus.BAD_REQUEST, "昵称不能为空"),
    LOGIN_IDENTIFIER_REQUIRED(40034, HttpStatus.BAD_REQUEST, "邮箱和手机号至少填一个"),
    EMAIL_INVALID(40035, HttpStatus.BAD_REQUEST, "邮箱格式好像不对，再检查一下"),
    PHONE_INVALID(40036, HttpStatus.BAD_REQUEST, "手机号格式好像不对，再检查一下"),
    USER_DUPLICATED(40901, HttpStatus.CONFLICT, "这个邮箱或手机号已经注册过了，直接登录吧"),

    // ========== 修改密码 ==========
    PASSWORD_OLD_INCORRECT(40117, HttpStatus.BAD_REQUEST, "旧密码不对，再试试"),
    PASSWORD_SAME_AS_OLD(40037, HttpStatus.BAD_REQUEST, "新密码不能和旧密码一样"),

    // ========== 家庭/成员权限 ==========
    FAMILY_NOT_FOUND(40403, HttpStatus.NOT_FOUND, "这个家庭不存在或已被删除"),
    FAMILY_NOT_MEMBER(40301, HttpStatus.FORBIDDEN, "您不是这个家庭的成员，无法访问"),
    FAMILY_HEADER_REQUIRED(40041, HttpStatus.BAD_REQUEST, "请在请求头中指定家庭ID"),
    ROLE_NO_PERMISSION(40302, HttpStatus.FORBIDDEN, "您的角色不能执行此操作"),
    OWNER_CANNOT_LEAVE(40051, HttpStatus.BAD_REQUEST, "家庭管理员需要先把管理员身份交给其他成员，才能退出家庭"),
    OWNER_TRANSFER_TARGET_INVALID(40052, HttpStatus.BAD_REQUEST, "请指定一个家庭内的成人成员作为新管理员"),
    FAMILY_DELETE_LAST_FAMILY(40054, HttpStatus.BAD_REQUEST, "这是您最后一个家庭，无法删除，请先创建或加入另一个家庭"),
    FAMILY_DELETE_NOT_OWNER(40303, HttpStatus.FORBIDDEN, "只有家庭管理员才能删除家庭"),
    NOT_IMPLEMENTED(50101, HttpStatus.NOT_IMPLEMENTED, "这个功能暂未开放，敬请期待"),

    // ========== 邀请码 ==========
    TEMPLATE_NOT_FOUND(40404, HttpStatus.NOT_FOUND, "模板不存在或无权访问"),
    SUBTASK_NOT_FOUND(40405, HttpStatus.NOT_FOUND, "子待办不存在"),
    NOTE_NOT_FOUND(40406, HttpStatus.NOT_FOUND, "便签不存在"),
    SUMMARY_LOG_NOT_FOUND(40407, HttpStatus.NOT_FOUND, "总结执行记录不存在或不属于该家庭"),
    INVITE_NOT_FOUND(40430, HttpStatus.NOT_FOUND, "邀请码不存在或已过期，请让家人重新生成"),
    INVITE_EXPIRED(40061, HttpStatus.BAD_REQUEST, "这个邀请码已经超过 7 天过期了，请让家人重新生成"),
    INVITE_COUNT_FULL(40062, HttpStatus.BAD_REQUEST, "这个邀请码次数已用完，请让家人重新生成"),
    INVITE_ALREADY_MEMBER(40963, HttpStatus.CONFLICT, "您已经是这个家庭的成员了"),

    // ========== Change 2 内部 Agent API：AI 提案校验类 4xx（46001-46499）==========
    AI_PROPOSAL_NOT_FOUND(46001, HttpStatus.BAD_REQUEST, "这个 AI 提案不存在或超过 24 小时已过期，请重新输入任务试试"),
    CATEGORY_MISMATCH(46002, HttpStatus.BAD_REQUEST, "分类不存在于当前家庭，检查 family_id 作用域"),
    TEMPLATE_INACTIVE(46003, HttpStatus.BAD_REQUEST, "此模板已停用，无法生成任务"),
    SUBTASK_BATCH_EMPTY(46004, HttpStatus.BAD_REQUEST, "批量新增子待办内容为空，至少 1 条"),
    SCHEDULE_PAST_DATE(46005, HttpStatus.BAD_REQUEST, "一次性定时日期已过去，选择今天之后的日期"),

    // ========== Change 2 内部 Agent API：自动化校验类 4xx（46500-46599）==========
    TRIGGER_SOURCE_NOT_COMPLETED(46501, HttpStatus.BAD_REQUEST, "依赖触发源任务状态不是 COMPLETED，暂不触发下游"),
    SUMMARY_ALREADY_RAN(46502, HttpStatus.BAD_REQUEST, "今日已自动总结过，仍想强制重跑请设置 force=true"),
    TEMPLATE_NO_VERSION(46503, HttpStatus.BAD_REQUEST, "此模板尚无版本记录，无法追加变更说明"),

    // ========== Change 2 内部 Agent API：AI 运行期 5xx（56001-56499）==========
    AI_DECOMPOSE_TIMEOUT(56001, HttpStatus.INTERNAL_SERVER_ERROR, "AI 拆解超时了（30 秒），点击重试按钮再来一次就行"),
    AI_JSON_TWICE_FAILED(56002, HttpStatus.INTERNAL_SERVER_ERROR, "AI 返回格式连续两次解析失败，已自动转手动创建模式"),
    SUMMARY_CATEGORY_TIMEOUT(56003, HttpStatus.INTERNAL_SERVER_ERROR, "单分类总结超时（60 秒），该分类跳过不阻断其它分类"),

    // ========== Change 2 内部 Agent API：工具服务 5xx（56501-56599）==========
    WEATHER_SERVICE_DOWN(56501, HttpStatus.SERVICE_UNAVAILABLE, "天气服务暂时不可用，日常模板的天气条件暂时跳过"),

    // ========== Change 7：功能总开关 5xx（50300-50399）==========
    AI_DECOMPOSE_DISABLED(50310, HttpStatus.SERVICE_UNAVAILABLE, "AI 拆解功能已关闭，请联系家庭管理员"),
    AI_PROPOSAL_ALREADY_FINALIZED(50311, HttpStatus.SERVICE_UNAVAILABLE, "AI 提案已经确认/归档/放弃完成啦，要重新拆解请从任务创建页重新发起哦"),
    AI_PROPOSAL_NO_CONTEXT(50312, HttpStatus.SERVICE_UNAVAILABLE, "缺少任务或档案上下文，提案无法继续，请重新发起拆解"),
    AI_PROPOSAL_EMPTY(50313, HttpStatus.SERVICE_UNAVAILABLE, "这次还没拆解出任何待办，先让 AI 再试一次吧"),

    // ========== Change 8：人员档案 + assignee_profile_id 过渡（41000-41099）==========
    PROFILE_NOT_FOUND(41000, HttpStatus.NOT_FOUND, "人员档案不存在"),
    PROFILE_TYPE_INVALID(41001, HttpStatus.BAD_REQUEST, "档案类型不合法，可选：宝宝/老人/成人/宠物/其他"),
    PROFILE_NAME_EMPTY(41002, HttpStatus.BAD_REQUEST, "人员姓名不能为空"),
    PROFILE_RELATION_EMPTY(41003, HttpStatus.BAD_REQUEST, "与本人关系不能为空"),
    PROFILE_PATCH_VALIDATION_FAIL(41004, HttpStatus.BAD_REQUEST, "档案信息填写有误，请检查后重试"),
    TASK_ASSIGNEE_PROFILE_NOT_FOUND(41005, HttpStatus.BAD_REQUEST, "被指派人档案不存在"),
    FAMILY_PROFILE_NAME_DUPLICATE(41006, HttpStatus.CONFLICT, "该家庭已有同名档案"),
    PROFILE_DELETE_HAS_TASKS(41007, HttpStatus.CONFLICT, "该档案有待办任务，无法删除"),
    ASSIGNEE_PROFILE_ID_CONFLICT(41008, HttpStatus.BAD_REQUEST, "指派给谁参数冲突，请仅使用新档案字段");

    private final int code;
    private final HttpStatus httpStatus;
    private final String msg;

    ResultCode(int code, HttpStatus httpStatus, String msg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getMsg() { return msg; }
}
