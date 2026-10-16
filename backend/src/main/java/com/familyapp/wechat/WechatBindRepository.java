package com.familyapp.wechat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * wechat_bind 仓储（Change 11；Change 12 加批量接收人查询）。
 */
public interface WechatBindRepository extends JpaRepository<WechatBind, Long> {

    /** 按微信 openid 查绑定关系（OAuth 回调主路径）。 */
    Optional<WechatBind> findByOpenid(String openid);

    /** 创建绑定级会话上下文前锁定绑定，避免首条并发消息重复建上下文。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM WechatBind b WHERE b.id = :id")
    Optional<WechatBind> findByIdForUpdate(@Param("id") Long id);

    boolean existsByOpenid(String openid);

    /** Change 12：按 user_id 批量查绑定（家庭消息接收人解析）。 */
    List<WechatBind> findAllByUserIdIn(Collection<Long> userIds);

    /** Change 12：按 user_id 查绑定（任务指派单人推送）。 */
    Optional<WechatBind> findByUserId(Long userId);
}
