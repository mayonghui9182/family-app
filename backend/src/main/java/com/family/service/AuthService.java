package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.config.JwtConfig;
import com.family.dto.CreateFamilyDTO;
import com.family.dto.JoinFamilyDTO;
import com.family.entity.Family;
import com.family.entity.FamilyInvite;
import com.family.entity.User;
import com.family.mapper.FamilyInviteMapper;
import com.family.mapper.FamilyMapper;
import com.family.mapper.UserMapper;
import com.family.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * 认证服务
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final FamilyMapper familyMapper;
    private final UserMapper userMapper;
    private final FamilyInviteMapper familyInviteMapper;
    private final JwtConfig jwtConfig;

    /**
     * 头像颜色数组（5种颜色）
     */
    private static final String[] AVATAR_COLORS = {
            "#FF8C42",
            "#6366F1",
            "#10B981",
            "#F59E0B",
            "#EC4899"
    };

    /**
     * 邀请码字符集（去掉易混淆的I/O/0/1）
     */
    private static final String INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /**
     * 邀请码长度
     */
    private static final int INVITE_CODE_LENGTH = 6;

    /**
     * 创建家庭组
     * 创建家庭组并创建管理员用户
     *
     * @param dto 创建家庭组DTO
     * @return 登录信息
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO createFamily(CreateFamilyDTO dto) {
        String inviteCode = generateInviteCode();

        Family family = new Family();
        family.setName(dto.getFamilyName());
        family.setInviteCode(inviteCode);
        family.setCreateTime(LocalDateTime.now());
        family.setUpdateTime(LocalDateTime.now());
        familyMapper.insert(family);

        User user = new User();
        user.setUsername(dto.getUserName());
        user.setNickname(dto.getUserName());
        user.setFamilyId(family.getId());
        user.setRole("admin");
        user.setAvatarColor(AVATAR_COLORS[0]);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        String token = jwtConfig.generateToken(user.getId(), family.getId(), user.getRole());

        return buildLoginVO(token, user, family);
    }

    /**
     * 加入家庭组
     * 根据邀请码加入家庭组并创建成员用户
     *
     * @param dto 加入家庭组DTO
     * @return 登录信息
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO joinFamily(JoinFamilyDTO dto) {
        FamilyInvite familyInvite = familyInviteMapper.selectOne(
                new LambdaQueryWrapper<FamilyInvite>()
                        .eq(FamilyInvite::getInviteCode, dto.getInviteCode())
        );

        if (familyInvite == null) {
            Family family = familyMapper.selectOne(
                    new LambdaQueryWrapper<Family>()
                            .eq(Family::getInviteCode, dto.getInviteCode())
            );
            if (family == null) {
                throw new RuntimeException("邀请码无效");
            }
            return joinFamilyByFamilyInvite(family, dto);
        }

        validateFamilyInvite(familyInvite);

        Family family = familyMapper.selectById(familyInvite.getFamilyId());
        if (family == null) {
            throw new RuntimeException("家庭不存在");
        }

        LoginVO loginVO = joinFamilyByFamilyInvite(family, dto);

        familyInvite.setUseCount(familyInvite.getUseCount() + 1);
        if (familyInvite.getUseCount() >= familyInvite.getMaxCount()) {
            familyInvite.setStatus("expired");
        }
        familyInvite.setUpdateTime(LocalDateTime.now());
        familyInviteMapper.updateById(familyInvite);

        return loginVO;
    }

    /**
     * 验证邀请码是否有效
     *
     * @param familyInvite 家庭邀请
     */
    private void validateFamilyInvite(FamilyInvite familyInvite) {
        if (!"active".equals(familyInvite.getStatus())) {
            throw new RuntimeException("邀请码已失效");
        }

        if (familyInvite.getExpireTime() != null && familyInvite.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("邀请码已过期");
        }

        if (familyInvite.getUseCount() >= familyInvite.getMaxCount()) {
            throw new RuntimeException("邀请码使用次数已达上限");
        }
    }

    /**
     * 通过家庭邀请加入家庭
     *
     * @param family 家庭
     * @param dto    加入家庭DTO
     * @return 登录信息
     */
    private LoginVO joinFamilyByFamilyInvite(Family family, JoinFamilyDTO dto) {
        Long memberCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getFamilyId, family.getId())
        );

        int colorIndex = memberCount.intValue() % AVATAR_COLORS.length;

        User user = new User();
        user.setUsername(dto.getUserName());
        user.setNickname(dto.getUserName());
        user.setFamilyId(family.getId());
        user.setRole("member");
        user.setAvatarColor(AVATAR_COLORS[colorIndex]);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        String token = jwtConfig.generateToken(user.getId(), family.getId(), user.getRole());

        return buildLoginVO(token, user, family);
    }

    /**
     * 生成邀请码
     * 6位大写字母数字，去掉易混淆的I/O/0/1
     *
     * @return 邀请码
     */
    private String generateInviteCode() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            int index = random.nextInt(INVITE_CODE_CHARS.length());
            sb.append(INVITE_CODE_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 构建登录返回VO
     *
     * @param token  Token
     * @param user   用户
     * @param family 家庭
     * @return 登录VO
     */
    private LoginVO buildLoginVO(String token, User user, Family family) {
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUserName(user.getUsername());
        vo.setFamilyId(family.getId());
        vo.setFamilyName(family.getName());
        vo.setInviteCode(family.getInviteCode());
        vo.setRole(user.getRole());
        return vo;
    }

}
