package com.familyapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;

@Component
public class FeatureGuardConfig {

    @Value("${familyapp.ai-decompose.enabled:true}")
    private boolean aiDecomposeEnabled;

    public boolean isAiDecomposeEnabled() {
        return aiDecomposeEnabled;
    }

    public void guardAiEnabledOrThrow() {
        if (!aiDecomposeEnabled) {
            throw new ApiException(ResultCode.AI_DECOMPOSE_DISABLED);
        }
    }

    public <T> Result<T> guardAiEnabledSse() {
        if (!aiDecomposeEnabled) {
            return Result.error(ResultCode.AI_DECOMPOSE_DISABLED);
        }
        return null;
    }
}
