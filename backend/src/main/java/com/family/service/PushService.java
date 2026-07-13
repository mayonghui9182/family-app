package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.common.context.UserContext;
import com.family.entity.PushDevice;
import com.family.mapper.PushDeviceMapper;
import com.family.vo.PushDeviceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 推送服务
 * 当前用日志模拟推送，后续接入真实推送渠道（个推/极光推送/Web Push）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PushService {

    private final PushDeviceMapper pushDeviceMapper;

    /**
     * 注册设备
     *
     * @param deviceToken 设备推送token
     * @param deviceType  设备类型（ios/android/web）
     * @param deviceName  设备名称
     * @return 注册的设备信息
     */
    public PushDeviceVO registerDevice(String deviceToken, String deviceType, String deviceName) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getDeviceToken, deviceToken);
        PushDevice existingDevice = pushDeviceMapper.selectOne(wrapper);

        PushDevice device;
        if (existingDevice != null) {
            device = existingDevice;
            device.setUserId(userId);
            device.setFamilyId(familyId);
            device.setDeviceType(deviceType);
            device.setDeviceName(deviceName);
            device.setIsEnabled(1);
            device.setUpdateTime(now);
            pushDeviceMapper.updateById(device);
            log.info("【推送服务】更新设备注册 deviceToken={}, userId={}", deviceToken, userId);
        } else {
            device = new PushDevice();
            device.setUserId(userId);
            device.setFamilyId(familyId);
            device.setDeviceToken(deviceToken);
            device.setDeviceType(deviceType);
            device.setDeviceName(deviceName);
            device.setIsEnabled(1);
            device.setCreateTime(now);
            device.setUpdateTime(now);
            pushDeviceMapper.insert(device);
            log.info("【推送服务】注册新设备 deviceToken={}, userId={}", deviceToken, userId);
        }

        return convertToVO(device);
    }

    /**
     * 注销设备
     *
     * @param deviceToken 设备推送token
     * @return 是否成功
     */
    public boolean unregisterDevice(String deviceToken) {
        Long userId = UserContext.getUserId();

        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getDeviceToken, deviceToken)
                .eq(PushDevice::getUserId, userId);
        int result = pushDeviceMapper.delete(wrapper);

        log.info("【推送服务】注销设备 deviceToken={}, userId={}, result={}", deviceToken, userId, result);
        return result > 0;
    }

    /**
     * 获取当前用户的设备列表
     *
     * @return 设备列表
     */
    public List<PushDeviceVO> getUserDevices() {
        Long userId = UserContext.getUserId();

        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getUserId, userId)
                .orderByDesc(PushDevice::getCreateTime);

        List<PushDevice> devices = pushDeviceMapper.selectList(wrapper);
        return devices.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    /**
     * 推送给指定用户
     *
     * @param userId  用户ID
     * @param title   推送标题
     * @param content 推送内容
     * @param extras  额外参数
     */
    public void pushToUser(Long userId, String title, String content, Map<String, Object> extras) {
        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getUserId, userId)
                .eq(PushDevice::getIsEnabled, 1);
        List<PushDevice> devices = pushDeviceMapper.selectList(wrapper);

        for (PushDevice device : devices) {
            doPush(device, title, content, extras);
        }

        log.info("【推送通知】推送给用户 userId={}, title={}, content={}, 设备数={}",
                userId, title, content, devices.size());
    }

    /**
     * 推送给全家
     *
     * @param familyId 家庭ID
     * @param title    推送标题
     * @param content  推送内容
     * @param extras   额外参数
     */
    public void pushToFamily(Long familyId, String title, String content, Map<String, Object> extras) {
        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getFamilyId, familyId)
                .eq(PushDevice::getIsEnabled, 1);
        List<PushDevice> devices = pushDeviceMapper.selectList(wrapper);

        for (PushDevice device : devices) {
            doPush(device, title, content, extras);
        }

        log.info("【推送通知】推送给家庭 familyId={}, title={}, content={}, 设备数={}",
                familyId, title, content, devices.size());
    }

    /**
     * 推送给所有设备
     *
     * @param title   推送标题
     * @param content 推送内容
     * @param extras  额外参数
     */
    public void pushToAll(String title, String content, Map<String, Object> extras) {
        LambdaQueryWrapper<PushDevice> wrapper = new LambdaQueryWrapper<PushDevice>()
                .eq(PushDevice::getIsEnabled, 1);
        List<PushDevice> devices = pushDeviceMapper.selectList(wrapper);

        for (PushDevice device : devices) {
            doPush(device, title, content, extras);
        }

        log.info("【推送通知】推送给所有设备 title={}, content={}, 设备数={}",
                title, content, devices.size());
    }

    /**
     * 推送给全家（兼容旧方法）
     *
     * @param familyId 家庭ID
     * @param title    推送标题
     * @param content  推送内容
     */
    public void pushToFamily(Long familyId, String title, String content) {
        pushToFamily(familyId, title, content, null);
    }

    /**
     * 推送给指定用户（兼容旧方法）
     *
     * @param userId  用户ID
     * @param title   推送标题
     * @param content 推送内容
     */
    public void pushToUser(Long userId, String title, String content) {
        pushToUser(userId, title, content, null);
    }

    /**
     * 执行推送（当前用日志模拟，后续接入真实推送渠道）
     *
     * @param device  设备信息
     * @param title   推送标题
     * @param content 推送内容
     * @param extras  额外参数
     */
    private void doPush(PushDevice device, String title, String content, Map<String, Object> extras) {
        log.info("【推送模拟】设备类型={}, deviceToken={}, title={}, content={}, extras={}",
                device.getDeviceType(), device.getDeviceToken(), title, content, extras);
    }

    /**
     * 转换为VO
     */
    private PushDeviceVO convertToVO(PushDevice device) {
        PushDeviceVO vo = new PushDeviceVO();
        vo.setId(device.getId());
        vo.setUserId(device.getUserId());
        vo.setFamilyId(device.getFamilyId());
        vo.setDeviceToken(device.getDeviceToken());
        vo.setDeviceType(device.getDeviceType());
        vo.setDeviceName(device.getDeviceName());
        vo.setIsEnabled(device.getIsEnabled());
        vo.setCreateTime(device.getCreateTime());
        vo.setUpdateTime(device.getUpdateTime());
        return vo;
    }
}
