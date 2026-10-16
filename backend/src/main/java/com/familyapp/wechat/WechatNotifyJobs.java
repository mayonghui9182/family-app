package com.familyapp.wechat;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Change 12 微信推送定时任务：每日 08:00 家庭任务早报。
 *
 * <p>沿用 TaskSchedulerJobs 单实例部署假设与逐家庭隔离模式：
 * 单家庭失败只记 error 不中断整批；无任务/无绑定的家庭在服务层自动跳过。
 */
@Component
public class WechatNotifyJobs {

    private static final Logger log = LoggerFactory.getLogger(WechatNotifyJobs.class);

    private final FamilyRepository familyRepository;
    private final WechatNotifyService notifyService;

    public WechatNotifyJobs(FamilyRepository familyRepository, WechatNotifyService notifyService) {
        this.familyRepository = familyRepository;
        this.notifyService = notifyService;
    }

    /** 每天 08:00（容器 TZ=Asia/Shanghai）：逐家庭推送今日到期/逾期早报。 */
    @Scheduled(cron = "0 0 8 * * ?")
    public void dailyDigestJob() {
        LocalDate today = LocalDate.now();
        List<Long> familyIds = familyRepository.findAll().stream().map(Family::getId).toList();
        log.info("微信早报推送开始，共 {} 个家庭", familyIds.size());
        int sent = 0;
        for (Long familyId : familyIds) {
            try {
                WechatNotifyService.DigestResult result = notifyService.digestForFamily(familyId, today);
                if (result.sent()) {
                    sent++;
                }
            } catch (Exception e) {
                log.error("家庭[{}]早报推送失败，隔离继续：{}", familyId, e.getMessage());
            }
        }
        log.info("微信早报推送结束：{}/{} 个家庭已发送", sent, familyIds.size());
    }
}
