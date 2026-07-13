package com.family.controller;

import com.family.common.result.Result;
import com.family.service.BaiduSpeechService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音控制器
 */
@Tag(name = "语音服务", description = "语音识别和语音合成相关接口")
@RestController
@RequestMapping("/speech")
@RequiredArgsConstructor
public class SpeechController {

    private final BaiduSpeechService baiduSpeechService;

    /**
     * 语音识别
     *
     * @param file   音频文件
     * @param format 音频格式（pcm/wav/amr/m4a）
     * @return 识别的文字
     */
    @Operation(summary = "语音识别", description = "将语音文件转换为文字")
    @PostMapping("/recognize")
    public Result<String> recognize(
            @Parameter(description = "音频文件")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "音频格式，默认wav")
            @RequestParam(value = "format", defaultValue = "wav") String format) {
        try {
            byte[] audioData = file.getBytes();
            String text = baiduSpeechService.recognize(audioData, format);
            return Result.success(text);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 语音合成
     *
     * @param text 要合成的文字
     * @param lang 语言（zh）
     * @return 音频流
     */
    @Operation(summary = "语音合成", description = "将文字转换为语音")
    @GetMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(
            @Parameter(description = "要合成的文字")
            @RequestParam("text") String text,
            @Parameter(description = "语言，默认zh")
            @RequestParam(value = "lang", defaultValue = "zh") String lang) {
        try {
            byte[] audioData = baiduSpeechService.synthesize(text, lang);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
            headers.setContentLength(audioData.length);
            headers.setContentDispositionFormData("attachment", "synthesize.mp3");
            return ResponseEntity.ok().headers(headers).body(audioData);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage().getBytes());
        }
    }
}
