package com.familyapp.family;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 邀请码生成器。使用无歧义字母表 ABCDEFGHJKMNPQRSTUVWXYZ（20 个字母，排除 0 O 1 I L）
 * 生成 6 位大写邀请码，并在 ACTIVE 状态下保证全局唯一（最多重试 10 次）。
 */
@Component
public class InviteCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final int MAX_RETRY = 10;

    private final SecureRandom random = new SecureRandom();
    private final InviteRepository inviteRepository;

    public InviteCodeGenerator(InviteRepository inviteRepository) {
        this.inviteRepository = inviteRepository;
    }

    /**
     * 生成一个 ACTIVE 状态下全局唯一的 6 位邀请码。
     * 连续 10 次碰撞则抛 SERVER_ERROR。
     */
    public String next() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String code = generate();
            if (inviteRepository.findByCodeAndStatus(code, InviteStatus.ACTIVE).isEmpty()) {
                return code;
            }
        }
        throw new ApiException(ResultCode.SERVER_ERROR,
                "服务器开小差了，无法生成邀请码，请稍后重试");
    }

    private String generate() {
        char[] buf = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
