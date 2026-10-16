package com.familyapp.wechat;

import com.familyapp.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 微信绑定关系实体（wechat_bind 表映射，Change 11）。
 *
 * <p>一个微信 openid 最多绑定一个 family-app 账号（uq_wechat_bind_openid）；
 * user 注销时绑定行级联删除（fk_wechat_bind_user ON DELETE CASCADE）。
 * openid 仅在服务端流转，永不返回前端（design.md D3）。
 */
@Entity
@Table(
    name = "wechat_bind",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_wechat_bind_openid",
        columnNames = "openid"),
    indexes = @Index(name = "idx_wechat_bind_user", columnList = "user_id")
)
public class WechatBind {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String openid;

    @ManyToOne(optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_wechat_bind_user"))
    private User user;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    @PrePersist
    void onCreate() {
        if (boundAt == null) {
            boundAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Instant getBoundAt() {
        return boundAt;
    }
}
