package com.familyapp.wechat.conversation;

/**
 * 微信会话家庭访问被拒绝。
 *
 * <p>无论家庭不存在、已删除还是成员关系失效，都使用同一错误，避免泄露家庭信息。</p>
 */
public class WechatFamilyAccessDeniedException extends RuntimeException {

    public WechatFamilyAccessDeniedException() {
        super("当前家庭不可用，请重新选择家庭");
    }
}
