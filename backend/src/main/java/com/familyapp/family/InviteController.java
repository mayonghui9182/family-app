package com.familyapp.family;

import com.familyapp.common.Result;
import com.familyapp.family.dto.AcceptInviteRequest;
import com.familyapp.family.dto.InviteCreateRequest;
import com.familyapp.family.dto.InviteResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 邀请码 Controller：创建、列表、取消、接受（加入家庭）。 */
@RestController
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/api/families/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<InviteResponse> create(@PathVariable Long id,
                                         @Valid @RequestBody InviteCreateRequest req) {
        return Result.ok(inviteService.createInvite(id, req));
    }

    @GetMapping("/api/families/{id}/invites")
    public Result<List<InviteResponse>> list(@PathVariable Long id,
                                             @RequestParam(required = false) InviteStatus status) {
        return Result.ok(inviteService.listInvites(id, status));
    }

    @DeleteMapping("/api/families/{id}/invites/{inviteId}")
    public Result<Void> cancel(@PathVariable Long id, @PathVariable Long inviteId) {
        inviteService.cancelInvite(id, inviteId);
        return Result.ok();
    }

    @PostMapping("/api/invites/accept")
    public Result<Void> accept(@Valid @RequestBody AcceptInviteRequest req) {
        inviteService.acceptInvite(req);
        return Result.ok();
    }
}
