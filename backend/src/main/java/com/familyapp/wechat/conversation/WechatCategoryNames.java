package com.familyapp.wechat.conversation;

import java.text.Normalizer;
import java.util.Locale;

/** 公众号分类建议的名称约束；不改变现有分类管理接口。 */
final class WechatCategoryNames {
    private WechatCategoryNames() { }
    static String normalize(String value) {
        if (value == null) return null;
        if (value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.FORMAT
                || Character.getType(point) == Character.PRIVATE_USE
                || Character.getType(point) == Character.SURROGATE
                || Character.getType(point) == Character.UNASSIGNED)) {
            throw new IllegalArgumentException("分类名称不能包含控制字符");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 50) {
            throw new IllegalArgumentException("分类名称须为一至五十字");
        }
        return normalized;
    }
    static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
