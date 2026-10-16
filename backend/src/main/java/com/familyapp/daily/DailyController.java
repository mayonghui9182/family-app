package com.familyapp.daily;

import com.familyapp.auth.UserContext;
import com.familyapp.common.Result;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.List;

/** 个人页面仅使用登录身份，不接受请求指定用户。 */
@RestController
@RequestMapping("/api/daily")
public class DailyController {
    private final DailyService service;
    public DailyController(DailyService service){this.service=service;}
    public record Setting(boolean enabled){}
    public record Revision(long version){}
    public record Edit(long version,List<String> order,List<String> focus){}
    public record Feedback(long version,String text){}
    private Long user(){return UserContext.current().userId();}
    @GetMapping("/settings") public Result<?> settings(){return Result.ok(service.settings(user()));}
    @PutMapping("/settings") public Result<?> settings(@RequestBody Setting body){return Result.ok(service.settings(user(),body.enabled()));}
    @GetMapping("/plans/{date}") public Result<?> plan(@PathVariable LocalDate date){return Result.ok(service.read(user(),date,"PLAN"));}
    @GetMapping("/reviews/{date}") public Result<?> review(@PathVariable LocalDate date){return Result.ok(service.read(user(),date,"REVIEW"));}
    @PostMapping("/plans/generate") public Result<?> generate(){service.generate(user(),"PLAN");return Result.ok(service.read(user(),service.today(),"PLAN"));}
    @PostMapping("/reviews/generate") public Result<?> reviewGenerate(){
        if(!service.reviewDue())
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"21:00后可生成当日复盘");
        service.generate(user(),"REVIEW");return Result.ok(service.read(user(),service.today(),"REVIEW"));
    }
    @PutMapping("/plans/{date}") public Result<?> edit(@PathVariable LocalDate date,@RequestBody Edit body){return Result.ok(service.edit(user(),date,body.version(),body.order(),body.focus()));}
    @PostMapping("/plans/{date}/confirm") public Result<?> confirm(@PathVariable LocalDate date,@RequestBody Revision body){return Result.ok(service.confirm(user(),date,body.version()));}
    @PatchMapping("/reviews/{date}") public Result<?> feedback(@PathVariable LocalDate date,@RequestBody Feedback body){return Result.ok(service.feedback(user(),date,body.version(),body.text()));}
    @Scheduled(fixedDelayString="${daily.poll-ms:60000}",initialDelayString="${daily.initial-ms:30000}")
    public void scan(){service.scan();}
}
