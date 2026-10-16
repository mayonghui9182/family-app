package com.familyapp.wechat;

/**
 * 微信入站消息处理状态（Change 14，wechat_inbound_message.status）。
 *
 * <p>五值固定，与 V12 的 chk_wim_status 检查约束一一对应；新增取值必须同步改迁移，
 * 否则生产 PG 会被约束直接拒掉。
 */
public enum WechatInboundStatus {

    /** 已受理待处理（队头状态）。 */
    PENDING,

    /** 轮询作业已捞起、正在建草稿与调拆解。 */
    RUNNING,

    /** 草稿已建成且确认链接已回推（回推失败也算 DONE，见 design.md D6）。 */
    DONE,

    /** 重试次数耗尽，已回推兜底话术。 */
    FAILED,

    /** 受理侧闸门拒绝（未绑定/无家庭/孩子成员/多家庭/开关关闭），不进入拆解。 */
    SKIPPED
}
