package com.familyapp.auth.recovery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    interface State {
        String getTokenHash();
        java.time.Instant getExpiresAt();
        java.time.Instant getUsedAt();
    }
    @org.springframework.data.jpa.repository.Query("select r.tokenHash as tokenHash, r.expiresAt as expiresAt, r.usedAt as usedAt from PasswordReset r where r.userId = :id")
    Optional<State> currentState(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update PasswordReset r set r.usedAt = :now where r.userId = :id")
    void markUsed(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("now") java.time.Instant now);
    Optional<PasswordReset> findByTokenHash(String tokenHash);
}
