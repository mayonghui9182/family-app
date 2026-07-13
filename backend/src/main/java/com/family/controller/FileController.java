package com.family.controller;

import com.family.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件控制器
 * <p>
 * 提供文件上传相关接口
 */
@Tag(name = "文件管理", description = "文件上传相关接口")
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class FileController {

    /**
     * 文件上传路径
     */
    @Value("${file.upload-path:./uploads}")
    private String uploadPath;

    /**
     * 上传语音文件
     * <p>
     * 文件保存路径：uploads/voice/yyyy/MM/dd/uuid.ext
     *
     * @param file 语音文件
     * @return 上传结果，包含文件访问路径和文件大小
     */
    @Operation(summary = "上传语音文件", description = "上传语音文件，返回文件访问路径")
    @PostMapping("/voice")
    public Result<Map<String, Object>> uploadVoice(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("上传文件不能为空");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = UUID.randomUUID().toString().replace("-", "");
            String newFilename = uuid + extension;

            String relativePath = "/voice/" + datePath + "/" + newFilename;
            String fullPath = uploadPath + relativePath;

            File destFile = new File(fullPath);
            if (!destFile.getParentFile().exists()) {
                destFile.getParentFile().mkdirs();
            }

            file.transferTo(destFile);

            Map<String, Object> result = new HashMap<>();
            result.put("url", "/uploads" + relativePath);
            result.put("size", file.getSize());

            return Result.success(result);
        } catch (IOException e) {
            return Result.error("文件上传失败：" + e.getMessage());
        }
    }
}
