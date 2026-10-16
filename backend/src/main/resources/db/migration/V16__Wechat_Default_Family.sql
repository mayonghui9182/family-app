-- 默认家庭只是绑定级偏好，不授予成员权限；保留 current_family_id 的既有语义。
ALTER TABLE wechat_chat_context ADD COLUMN default_family_id BIGINT NULL
    REFERENCES families(id) ON DELETE SET NULL;
-- 回滚先关闭新入口和 worker，保留偏好数据；无生产偏好时可删除本新增列。
