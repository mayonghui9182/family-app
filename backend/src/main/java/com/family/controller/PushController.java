package com.family.controller;

import com.family.common.result.Result;
import com.family.service.PushService;
import com.family.vo.PushDeviceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推送控制器
 */
@Tag(name = "推送管理", description = "推送设备注册和管理相关接口")
@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushController {

    private final PushService pushService;

    /**
     * 注册设备
     *
     * @param deviceToken 设备推送token
     * @param deviceType  设备类型（ios/android/web）
     * @param deviceName  设备名称
     * @return 注册的设备信息
     */
    @Operation(summary = "注册设备", description = "注册推送设备，用于接收推送通知")
    @PostMapping("/register")
    public Result<PushDeviceVO> registerDevice(
            @Parameter(description = "设备推送token")
            @RequestParam("deviceToken") String deviceToken,
            @Parameter(description = "设备类型（ios/android/web）")
            @RequestParam("deviceType") String deviceType,
            @Parameter(description = "设备名称")
            @RequestParam(value = "deviceName", required = false) String deviceName) {
        PushDeviceVO device = pushService.registerDevice(deviceToken, deviceType, deviceName);
        return Result.success(device);
    }

    /**
     * 注销设备
     *
     * @param deviceToken 设备推送token
     * @return 操作结果
     */
    @Operation(summary = "注销设备", description = "注销推送设备，停止接收推送通知")
    @PostMapping("/unregister")
    public Result<Void> unregisterDevice(
            @Parameter(description = "设备推送token")
            @RequestParam("deviceToken") String deviceToken) {
        pushService.unregisterDevice(deviceToken);
        return Result.success();
    }

    /**
     * 获取当前用户的设备列表
     *
     * @return 设备列表
     */
    @Operation(summary = "获取设备列表", description = "获取当前用户注册的所有推送设备")
    @GetMapping("/devices")
    public Result<List<PushDeviceVO>> getDevices() {
        List<PushDeviceVO> devices = pushService.getUserDevices();
        return Result.success(devices);
    }
}
