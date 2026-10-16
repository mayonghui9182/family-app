package com.familyapp.family;

import com.familyapp.common.Result;
import com.familyapp.family.dto.ChangeRoleRequest;
import com.familyapp.family.dto.CreateFamilyRequest;
import com.familyapp.family.dto.FamilyResponse;
import com.familyapp.family.dto.FamilySummaryResponse;
import com.familyapp.family.dto.TransferOwnerRequest;
import com.familyapp.family.dto.UpdateFamilyRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 家庭 Controller：家庭增删改查与成员管理（转让、改角色、踢人、退出）。 */
@RestController
@RequestMapping("/api/families")
public class FamilyController {

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<FamilyResponse> create(@Valid @RequestBody CreateFamilyRequest req) {
        return Result.ok(familyService.createFamily(req));
    }

    @GetMapping
    public Result<List<FamilySummaryResponse>> list() {
        return Result.ok(familyService.listMyFamilies());
    }

    @GetMapping("/{id}")
    public Result<FamilyResponse> get(@PathVariable Long id) {
        return Result.ok(familyService.getFamilyDetail(id));
    }

    @PutMapping("/{id}")
    public Result<FamilyResponse> update(@PathVariable Long id,
                                         @Valid @RequestBody UpdateFamilyRequest req) {
        return Result.ok(familyService.updateFamily(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        familyService.deleteFamily(id);
        return Result.ok();
    }

    @PostMapping("/{id}/leave")
    public Result<Void> leave(@PathVariable Long id) {
        familyService.leaveFamily(id);
        return Result.ok();
    }

    @PostMapping("/{id}/transfer-owner")
    public Result<Void> transferOwner(@PathVariable Long id,
                                      @Valid @RequestBody TransferOwnerRequest req) {
        familyService.transferOwner(id, req);
        return Result.ok();
    }

    @PostMapping("/{id}/members/{userId}")
    public Result<Void> changeRole(@PathVariable Long id,
                                   @PathVariable Long userId,
                                   @Valid @RequestBody ChangeRoleRequest req) {
        familyService.changeRole(id, userId, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> kickMember(@PathVariable Long id,
                                   @PathVariable Long userId) {
        familyService.kickMember(id, userId);
        return Result.ok();
    }
}
