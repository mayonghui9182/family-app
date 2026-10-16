## MODIFIED Requirements

### Requirement: Invite 邀请码 20 字母表 6 位大写无歧义 + accept 5 精确码 + 原子 consume
InviteCodeGenerator alphabet SHALL be ABCDEFGHJKMNPQRSTUVWXYZ (20 letters; excludes visually ambiguous 0 O 1 I L) producing 6 uppercase chars. UNIQUE collision loops up to 10 attempts; beyond 10 -> ApiException 500 SERVER_ERROR 服务器开小差了，无法生成邀请码，请稍后重试. Four invite endpoints: POST /api/families/{id}/invites (maxUses 1-100 default 1, ttlDays 1-30 default 7, caller role OWNER/ADULT 403 ROLE_NO_PERMISSION 您的角色不能创建邀请，请让家长帮忙操作 for CHILD); GET /api/families/{id}/invites optional status filter OWNER/ADULT; DELETE /api/families/{id}/invites/{inviteId} OWNER OR (ADULT who is also invite.inviter_id) cancels by UPDATE status=CANCELLED; POST /api/invites/accept any logged-in user no X-Family-Id with body {code}. Accept SHALL atomically within single @Transactional: (a) findActiveByCode(code) empty -> 40430 INVITE_NOT_FOUND 邀请码不存在或已过期，请让家人重新生成; (b) now()>expires_at status=ACTIVE -> UPDATE EXPIRED then 40061 INVITE_EXPIRED 这个邀请码已经超过 7 天过期了，请让家人重新生成; (c) used_count>=max_uses status=ACTIVE -> UPDATE USED then 40062 INVITE_COUNT_FULL 这个邀请码次数已用完，请让家人重新生成; (d) user already active member of same family -> 40963 INVITE_ALREADY_MEMBER 您已经是这个家庭的成员了; (e) otherwise used_count++ if >=max_uses status=USED, accepted_at=NOW, INSERT a first-time family_members row with role=ADULT, or reactivate a previous left_at!=null row for the same family+user by clearing left_at while preserving its existing role and refreshing joined_at.

#### Scenario: 10000 generated invite codes contain zero ambiguous characters and are 6 chars uppercase
- **WHEN** InviteCodeGenerator.next generates 10000 codes collected in a test
- **THEN** every code length exactly 6; every character from allowed set; no occurrence of letter 0 O 1 I L lowercase anywhere

#### Scenario: Expired invite accept -> INVITE_EXPIRED and status updated to EXPIRED
- **GIVEN** Invite I status=ACTIVE expires_at=NOW minus 8 days
- **WHEN** User U not member calls POST /api/invites/accept code=I.code
- **THEN** invites.status becomes EXPIRED; response 400 code INVITE_EXPIRED Chinese 这个邀请码已经超过 7 天过期了，请让家人重新生成

#### Scenario: Valid accept atomically consumes invite and inserts family_members; two concurrent accepts on max_uses=2 both succeed
- **GIVEN** Invite I status=ACTIVE max_uses=2 used_count=0; users U1 U2 not in I.family
- **WHEN** U1 accept and U2 accept run concurrently then both complete
- **THEN** invites.used_count=2 and status=USED; family_members gains exactly two new rows role=ADULT; no rows double-counted or missing

#### Scenario: CHILD role invite create blocked 403 with Chinese hint to ask parent
- **GIVEN** C is CHILD of family 1
- **WHEN** C calls POST /api/families/1/invites
- **THEN** response 403 ROLE_NO_PERMISSION Chinese 您的角色不能创建邀请，请让家长帮忙操作; no invite row inserted

#### Scenario: Former CHILD rejoining through an invite preserves CHILD role
- **GIVEN** User C previously left family F with role CHILD and has a valid invite for F
- **WHEN** C accepts the invite
- **THEN** the existing membership is reactivated with role CHILD, left_at cleared, and joined_at refreshed
