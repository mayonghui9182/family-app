package com.familyapp.profile;

import com.familyapp.common.Result;
import com.familyapp.profile.dto.CreatePersonProfileRequest;
import com.familyapp.profile.dto.PersonProfileResponse;
import com.familyapp.profile.dto.ProfileOption;
import com.familyapp.profile.dto.UpdatePersonProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人员档案 REST 控制器（P-1~P-6）
 *
 * <p>设计依据：spec §2 P-1~P-6 + plan Step 6 + PersonProfileControllerTest（8 用例）
 *
 * <p>端点清单：
 * <ol>
 *   <li>P-1 GET  /api/v1/person-profiles            ?profileType=BABY|ELDER|...  列档案（全家可读）</li>
 *   <li>P-2 POST /api/v1/person-profiles                                           新建档案（CHILD→40303）</li>
 *   <li>P-3 GET  /api/v1/person-profiles/{id}                                      详情（家庭内可读）</li>
 *   <li>P-4 PUT  /api/v1/person-profiles/{id}                                      更新 + profileDataPatch + removeKeys（CHILD→40303）</li>
 *   <li>P-5 DELETE /api/v1/person-profiles/{id}                                    软删除（非 OWNER→40304；待办绑定→40901）</li>
 *   <li>P-6 GET  /api/v1/person-profiles/options                                   5 种档案类型下拉（中文 profileTypeName）</li>
 * </ol>
 *
 * <p>HTTP 状态码选择（与 PersonProfileControllerTest 断言对齐）：
 * <ul>
 *   <li>P-2 成功 → 201 CREATED（CategoryController 同款）</li>
 *   <li>P-1/P-3/P-4/P-6 成功 → 200 OK</li>
 *   <li>P-5 成功 → 204 NO_CONTENT（软删除不返回 body，匹配 test 断言 isNoContent）</li>
 *   <li>权限失败 → GlobalExceptionHandler.handleResult 按 ResultException.code 前缀映射（403xx→403，404xx→404…）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/person-profiles")
public class PersonProfileController {

    private final PersonProfileService service;

    public PersonProfileController(PersonProfileService service) {
        this.service = service;
    }

    // ==================== P-1：列档案（全家可读） ====================

    @GetMapping
    public Result<List<PersonProfileResponse>> list(
            @RequestParam(required = false, name = "profileType") String profileType) {
        return Result.ok(service.list(profileType));
    }

    // ==================== P-6：档案类型下拉（中文） ====================
    // ★ 放在 /{id} 前，避免被 Spring 路径匹配当作 id 解析

    @GetMapping("/options")
    public Result<List<ProfileOption>> options() {
        return Result.ok(List.of(
                new ProfileOption("BABY", "宝宝"),
                new ProfileOption("ELDER", "老人"),
                new ProfileOption("ADULT", "成人"),
                new ProfileOption("PET", "宠物"),
                new ProfileOption("OTHER", "其他")
        ));
    }

    // ==================== P-2：新建（CHILD→40303） ====================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<PersonProfileResponse> create(@RequestBody CreatePersonProfileRequest req) {
        return Result.ok(service.create(req));
    }

    // ==================== P-3：详情（家庭内可读） ====================

    @GetMapping("/{id}")
    public Result<PersonProfileResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    // ==================== P-4：更新 + profileDataPatch + removeKeys ====================

    @PutMapping("/{id}")
    public Result<PersonProfileResponse> update(@PathVariable Long id,
                                                @RequestBody UpdatePersonProfileRequest req) {
        return Result.ok(service.update(id, req));
    }

    // ==================== P-5：软删除（OWNER；待办绑定=40901） ====================
    // 返回 204 No Content（无 body），与 PersonProfileControllerTest 断言 status().isNoContent() 对齐

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
