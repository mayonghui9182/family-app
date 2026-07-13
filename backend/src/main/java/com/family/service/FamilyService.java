package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.common.context.UserContext;
import com.family.entity.Family;
import com.family.entity.FamilyInvite;
import com.family.entity.User;
import com.family.mapper.FamilyInviteMapper;
import com.family.mapper.FamilyMapper;
import com.family.mapper.UserMapper;
import com.family.vo.FamilyInviteVO;
import com.family.vo.MemberVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 家庭服务
 */
@Service
@RequiredArgsConstructor
public class FamilyService {

    private final FamilyMapper familyMapper;
    private final UserMapper userMapper;
    private final FamilyInviteMapper familyInviteMapper;

    /**
     * 邀请码字符集（去掉易混淆的I/O/0/1）
     */
    private static final String INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /**
     * 邀请码长度
     */
    private static final int INVITE_CODE_LENGTH = 6;

    /**
     * 获取家庭信息
     *
     * @return 家庭信息
     */
    public Family getFamilyInfo() {
        Long familyId = UserContext.getFamilyId();
        return familyMapper.selectById(familyId);
    }

    /**
     * 获取当前家庭所有成员列表
     *
     * @return 成员列表
     */
    public List<MemberVO> getMembers() {
        Long familyId = UserContext.getFamilyId();
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getFamilyId, familyId)
                        .orderByAsc(User::getCreateTime)
        );
        return users.stream()
                .map(this::convertUserToMemberVO)
                .collect(Collectors.toList());
    }

    /**
     * 获取成员详情
     *
     * @param id 用户ID
     * @return 成员详情
     */
    public MemberVO getMemberDetail(Long id) {
        Long familyId = UserContext.getFamilyId();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, id)
                        .eq(User::getFamilyId, familyId)
        );
        if (user == null) {
            return null;
        }
        return convertUserToMemberVO(user);
    }

    /**
     * 更新成员角色（仅admin可操作）
     *
     * @param userId 用户ID
     * @param role   角色
     * @return 更新后的成员信息
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberVO updateMemberRole(Long userId, String role) {
        checkAdminPermission();

        Long familyId = UserContext.getFamilyId();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getFamilyId, familyId)
        );
        if (user == null) {
            throw new RuntimeException("成员不存在");
        }

        user.setRole(role);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        return convertUserToMemberVO(user);
    }

    /**
     * 移除成员（仅admin可操作，不能移除自己）
     *
     * @param userId 用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long userId) {
        checkAdminPermission();

        Long currentUserId = UserContext.getUserId();
        if (currentUserId.equals(userId)) {
            throw new RuntimeException("不能移除自己");
        }

        Long familyId = UserContext.getFamilyId();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getFamilyId, familyId)
        );
        if (user == null) {
            throw new RuntimeException("成员不存在");
        }

        user.setFamilyId(null);
        user.setRole(null);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 生成邀请码
     *
     * @param maxCount    最大使用次数
     * @param expireHours 过期时间（小时）
     * @return 邀请码信息
     */
    @Transactional(rollbackFor = Exception.class)
    public FamilyInviteVO generateInviteCode(Integer maxCount, Integer expireHours) {
        checkAdminPermission();

        Long familyId = UserContext.getFamilyId();
        Long inviterId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        String inviteCode = generateUniqueInviteCode();

        FamilyInvite familyInvite = new FamilyInvite();
        familyInvite.setFamilyId(familyId);
        familyInvite.setInviterId(inviterId);
        familyInvite.setInviteCode(inviteCode);
        familyInvite.setMaxCount(maxCount != null ? maxCount : 10);
        familyInvite.setUseCount(0);
        familyInvite.setStatus("active");
        if (expireHours != null && expireHours > 0) {
            familyInvite.setExpireTime(now.plusHours(expireHours));
        }
        familyInvite.setCreateTime(now);
        familyInvite.setUpdateTime(now);
        familyInviteMapper.insert(familyInvite);

        return convertFamilyInviteToVO(familyInvite);
    }

    /**
     * 获取邀请码列表
     *
     * @return 邀请码列表
     */
    public List<FamilyInviteVO> getInviteList() {
        Long familyId = UserContext.getFamilyId();
        List<FamilyInvite> invites = familyInviteMapper.selectList(
                new LambdaQueryWrapper<FamilyInvite>()
                        .eq(FamilyInvite::getFamilyId, familyId)
                        .orderByDesc(FamilyInvite::getCreateTime)
        );

        List<Long> inviterIds = invites.stream()
                .map(FamilyInvite::getInviterId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, String> userNameMap = userMapper.selectBatchIds(inviterIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        return invites.stream().map(invite -> {
            FamilyInviteVO vo = convertFamilyInviteToVO(invite);
            vo.setInviterName(userNameMap.get(invite.getInviterId()));
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 禁用邀请码
     *
     * @param inviteId 邀请ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableInvite(Long inviteId) {
        checkAdminPermission();

        Long familyId = UserContext.getFamilyId();
        FamilyInvite invite = familyInviteMapper.selectOne(
                new LambdaQueryWrapper<FamilyInvite>()
                        .eq(FamilyInvite::getId, inviteId)
                        .eq(FamilyInvite::getFamilyId, familyId)
        );
        if (invite == null) {
            throw new RuntimeException("邀请码不存在");
        }

        invite.setStatus("disabled");
        invite.setUpdateTime(LocalDateTime.now());
        familyInviteMapper.updateById(invite);
    }

    /**
     * 检查管理员权限
     */
    private void checkAdminPermission() {
        String role = UserContext.getRole();
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限操作，需要管理员角色");
        }
    }

    /**
     * 生成唯一邀请码
     *
     * @return 邀请码
     */
    private String generateUniqueInviteCode() {
        Random random = new Random();
        String inviteCode;
        int maxAttempts = 10;
        int attempts = 0;
        do {
            StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
            for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
                int index = random.nextInt(INVITE_CODE_CHARS.length());
                sb.append(INVITE_CODE_CHARS.charAt(index));
            }
            inviteCode = sb.toString();
            attempts++;
        } while (inviteCodeExists(inviteCode) && attempts < maxAttempts);
        return inviteCode;
    }

    /**
     * 检查邀请码是否已存在
     *
     * @param inviteCode 邀请码
     * @return 是否存在
     */
    private boolean inviteCodeExists(String inviteCode) {
        Long count = familyInviteMapper.selectCount(
                new LambdaQueryWrapper<FamilyInvite>()
                        .eq(FamilyInvite::getInviteCode, inviteCode)
        );
        return count != null && count > 0;
    }

    /**
     * 转换用户为成员VO
     *
     * @param user 用户
     * @return 成员VO
     */
    private MemberVO convertUserToMemberVO(User user) {
        MemberVO vo = new MemberVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setAvatarColor(user.getAvatarColor());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    /**
     * 转换家庭邀请为VO
     *
     * @param invite 家庭邀请
     * @return 邀请VO
     */
    private FamilyInviteVO convertFamilyInviteToVO(FamilyInvite invite) {
        FamilyInviteVO vo = new FamilyInviteVO();
        vo.setId(invite.getId());
        vo.setInviteCode(invite.getInviteCode());
        vo.setInviterId(invite.getInviterId());
        vo.setMaxCount(invite.getMaxCount());
        vo.setUseCount(invite.getUseCount());
        vo.setStatus(invite.getStatus());
        vo.setExpireTime(invite.getExpireTime());
        vo.setCreateTime(invite.getCreateTime());
        return vo;
    }

}
