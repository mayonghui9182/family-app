package com.familyapp.auth;

import com.familyapp.auth.dto.AuthResponse;
import com.familyapp.auth.dto.LoginRequest;
import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.auth.dto.RegisterRequest;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyService;
import com.familyapp.family.InviteService;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.FamilySummaryResponse;
import com.familyapp.user.User;
import com.familyapp.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 认证服务：注册、登录、登出、token 生成、获取当前用户信息。
 * <p>注册即登录（直接返回 token），适配老人用户免二次操作。</p>
 */
@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserRepository userRepository;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final PasswordEncoder passwordEncoder;
    private final FamilyService familyService;
    private final InviteService inviteService;

    public AuthService(UserRepository userRepository,
                       FamilyRepository familyRepository,
                       FamilyMemberRepository familyMemberRepository,
                       PasswordEncoder passwordEncoder,
                       FamilyService familyService,
                       InviteService inviteService) {
        this.userRepository = userRepository;
        this.familyRepository = familyRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.passwordEncoder = passwordEncoder;
        this.familyService = familyService;
        this.inviteService = inviteService;
    }

    /**
     * 注册新用户；可创建家庭、按邀请码加入家庭，或暂不选择家庭。
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        // 校验顺序按 spec
        String password = req.getPassword();
        if (password == null || password.length() < 8) {
            throw new ApiException(ResultCode.PASSWORD_TOO_SHORT);
        }
        if (password.length() > 64) {
            throw new ApiException(ResultCode.PASSWORD_TOO_LONG);
        }
        String nickname = req.getNickname();
        if (nickname == null || nickname.isBlank()) {
            throw new ApiException(ResultCode.NICKNAME_REQUIRED);
        }
        String email = req.getEmail();
        String phone = req.getPhone();
        boolean emailBlank = email == null || email.isBlank();
        boolean phoneBlank = phone == null || phone.isBlank();
        if (emailBlank && phoneBlank) {
            throw new ApiException(ResultCode.LOGIN_IDENTIFIER_REQUIRED);
        }
        if (!emailBlank && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ApiException(ResultCode.EMAIL_INVALID);
        }
        if (!phoneBlank && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new ApiException(ResultCode.PHONE_INVALID);
        }
        if (!emailBlank && userRepository.findByEmailAndDeletedAtIsNull(email).isPresent()) {
            throw new ApiException(ResultCode.USER_DUPLICATED);
        }
        if (!phoneBlank && userRepository.findByPhoneAndDeletedAtIsNull(phone).isPresent()) {
            throw new ApiException(ResultCode.USER_DUPLICATED);
        }

        Instant now = Instant.now();

        // 1. INSERT users
        User user = new User();
        user.setEmail(emailBlank ? null : email);
        user.setPhone(phoneBlank ? null : phone);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        boolean createFamily = !Boolean.FALSE.equals(req.getCreateFamily());
        String inviteCode = req.getInviteCode() == null ? "" : req.getInviteCode().trim();
        if (createFamily && !inviteCode.isEmpty()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                    "请选择创建家庭或输入邀请码加入家庭");
        }

        Long familyId = null;
        if (createFamily) {
            CreateFamilyRequest familyRequest = new CreateFamilyRequest();
            familyRequest.setName((req.getFamilyName() == null || req.getFamilyName().isBlank())
                    ? nickname + "的家" : req.getFamilyName());
            familyId = familyService.createFamily(familyRequest, user.getId()).id();
        } else if (!inviteCode.isEmpty()) {
            familyId = inviteService.acceptInviteForUser(inviteCode, user.getId());
        }

        // 4. 生成 token
        generateNewToken(user);

        return new AuthResponse(
                user.getAccessToken(),
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                familyId
        );
    }

    /**
     * 登录。email/phone 二选一，校验密码后生成 token，返回用户所有活跃家庭列表。
     */
    @Transactional
    public LoginResponse login(LoginRequest req) {
        String email = req.getEmail();
        String phone = req.getPhone();
        boolean emailBlank = email == null || email.isBlank();
        boolean phoneBlank = phone == null || phone.isBlank();

        User user;
        if (!emailBlank) {
            Optional<User> opt = userRepository.findByEmailAndDeletedAtIsNull(email);
            if (opt.isEmpty()) {
                throw new ApiException(ResultCode.AUTH_EMAIL_NOT_FOUND);
            }
            user = opt.get();
            if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                throw new ApiException(ResultCode.AUTH_EMAIL_WRONG_PASSWORD);
            }
        } else if (!phoneBlank) {
            Optional<User> opt = userRepository.findByPhoneAndDeletedAtIsNull(phone);
            if (opt.isEmpty()) {
                throw new ApiException(ResultCode.AUTH_PHONE_NOT_FOUND);
            }
            user = opt.get();
            if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                throw new ApiException(ResultCode.AUTH_PHONE_WRONG_PASSWORD);
            }
        } else {
            throw new ApiException(ResultCode.LOGIN_IDENTIFIER_REQUIRED);
        }

        // 有 token 就复用（支持多会话；logout 会清 token，下次 login 自动生成新的）
        if (user.getAccessToken() == null || user.getAccessToken().isBlank()) {
            generateNewToken(user);
        }

        return buildLoginResponse(user);
    }

    /**
     * 登出。从 UserContext 取 userId，清除 token。
     */
    @Transactional
    public void logout() {
        Long userId = UserContext.current().userId();
        Optional<User> opt = userRepository.findById(userId);
        if (opt.isEmpty()) {
            return;
        }
        User user = opt.get();
        user.setAccessToken(null);
        user.setTokenUpdatedAt(null);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    /**
     * 获取当前登录用户信息及其所有活跃家庭列表。
     * <p>不重新生成 token，直接返回当前 accessToken + 用户信息 + 家庭列表。</p>
     */
    @Transactional(readOnly = true)
    public LoginResponse me() {
        Long userId = UserContext.current().userId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResultCode.UNAUTHORIZED));
        return buildLoginResponse(user);
    }

    /**
     * 修改密码：旧密码二次确认 → 新密码长度校验 → 不能与旧密码一致 → 落库。
     * <p>修改成功后旧 token 仍然有效（不强制重新登录，老人友好）。</p>
     */
    @Transactional
    public void changePassword(com.familyapp.auth.dto.ChangePasswordRequest req) {
        Long userId = UserContext.current().userId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResultCode.UNAUTHORIZED));
        String oldPwd = req.getOldPassword();
        String newPwd = req.getNewPassword();
        if (oldPwd == null || oldPwd.isBlank()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        if (newPwd == null || newPwd.length() < 8) {
            throw new ApiException(ResultCode.PASSWORD_TOO_SHORT);
        }
        if (newPwd.length() > 64) {
            throw new ApiException(ResultCode.PASSWORD_TOO_LONG);
        }
        if (!passwordEncoder.matches(oldPwd, user.getPasswordHash())) {
            throw new ApiException(ResultCode.PASSWORD_OLD_INCORRECT);
        }
        if (oldPwd.equals(newPwd)) {
            throw new ApiException(ResultCode.PASSWORD_SAME_AS_OLD);
        }
        user.setPasswordHash(passwordEncoder.encode(newPwd));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    /**
     * 构建登录响应（token + 用户昵称 + 家庭列表）。login 与 me 复用；
     * Change 11 微信免密登录（WechatService）同样复用，保证 token 与密码登录同权。
     */
    public LoginResponse buildLoginResponse(User user) {
        List<FamilyMember> memberships = familyMemberRepository.findAllByUserIdAndLeftAtIsNull(user.getId());
        List<FamilySummaryResponse> families = new ArrayList<>(memberships.size());
        for (FamilyMember m : memberships) {
            Optional<Family> famOpt = familyRepository.findById(m.getFamilyId());
            if (famOpt.isEmpty()) {
                continue;
            }
            Family f = famOpt.get();
            families.add(new FamilySummaryResponse(
                    f.getId(),
                    f.getName(),
                    m.getRole(),
                    m.getRole().getChineseName(),
                    m.getJoinedAt()
            ));
        }
        return new LoginResponse(
                user.getAccessToken(),
                user.getId(),
                user.getNickname(),
                families
        );
    }

    /**
     * 确保用户持有有效 accessToken：为空则补发并落库（Change 11 微信免密登录复用，
     * 与密码登录「有 token 就复用、被 logout 清过则补发」同款规则）。
     */
    public void ensureAccessToken(User user) {
        if (user.getAccessToken() == null || user.getAccessToken().isBlank()) {
            generateNewToken(user);
        }
    }

    /**
     * 生成新的 64 位 hex token，写入 user.accessToken + tokenUpdatedAt，并 persist 到 DB。
     */
    private void generateNewToken(User user) {
        try {
            SecureRandom random = SecureRandom.getInstanceStrong();
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = HexFormat.of().formatHex(bytes);
            user.setAccessToken(token);
            user.setTokenUpdatedAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            userRepository.save(user);
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException(ResultCode.INTERNAL_ERROR);
        }
    }
}
