package com.familyapp.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问层。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.email = :email and u.deletedAt is null")
    Optional<User> lockActiveByEmail(@org.springframework.data.repository.query.Param("email") String email);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> lockActiveById(@org.springframework.data.repository.query.Param("id") Long id);


    /**
     * 通过邮箱查找未删除的用户（邮箱注册/登录使用）。
     * 对应 partial unique index uk_users_email (WHERE deleted_at IS NULL)。
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /**
     * 通过手机号查找未删除的用户（手机号注册/登录使用）。
     * 对应 partial unique index uk_users_phone (WHERE deleted_at IS NULL)。
     */
    Optional<User> findByPhoneAndDeletedAtIsNull(String phone);

    /**
     * 通过访问令牌查找用户（AuthInterceptor 鉴权使用）。
     * 对应 partial index idx_users_access_token (WHERE access_token IS NOT NULL)。
     */
    Optional<User> findByAccessToken(String accessToken);
}