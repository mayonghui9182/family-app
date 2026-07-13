package com.family.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.family.config.BaiduSpeechConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 百度语音服务
 * 提供语音识别（ASR）和语音合成（TTS）功能
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BaiduSpeechService {

    private final BaiduSpeechConfig speechConfig;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 缓存的access_token
     */
    private String cachedAccessToken;

    /**
     * access_token过期时间
     */
    private LocalDateTime tokenExpireTime;

    /**
     * 语音识别（ASR）
     *
     * @param audioData 音频数据
     * @param format    音频格式（pcm/wav/amr/m4a）
     * @return 识别的文字
     */
    public String recognize(byte[] audioData, String format) {
        if (!speechConfig.isConfigured()) {
            log.warn("百度语音服务未配置，无法进行语音识别");
            throw new RuntimeException("语音识别服务未配置，请联系管理员");
        }

        try {
            String accessToken = getAccessToken();
            String audioBase64 = Base64.encode(audioData);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("format", format != null ? format : "wav");
            requestBody.put("rate", 16000);
            requestBody.put("channel", 1);
            requestBody.put("cuid", "family-backend");
            requestBody.put("token", accessToken);
            requestBody.put("speech", audioBase64);
            requestBody.put("len", audioData.length);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    speechConfig.getAsrUrl(), request, String.class);

            JSONObject result = JSONUtil.parseObj(response.getBody());
            if (result.getInt("err_no") != null && result.getInt("err_no") == 0) {
                return result.getJSONArray("result").getStr(0);
            } else {
                log.error("语音识别失败: err_no={}, err_msg={}",
                        result.get("err_no"), result.get("err_msg"));
                throw new RuntimeException("语音识别失败：" + result.get("err_msg"));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("语音识别异常", e);
            throw new RuntimeException("语音识别服务异常，请稍后重试");
        }
    }

    /**
     * 语音合成（TTS）
     *
     * @param text 要合成的文字
     * @param lang 语言（zh）
     * @return 音频字节数组
     */
    public byte[] synthesize(String text, String lang) {
        if (!speechConfig.isConfigured()) {
            log.warn("百度语音服务未配置，无法进行语音合成");
            throw new RuntimeException("语音合成服务未配置，请联系管理员");
        }

        try {
            String accessToken = getAccessToken();

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("tex", text);
            params.add("tok", accessToken);
            params.add("cuid", "family-backend");
            params.add("ctp", "1");
            params.add("lan", lang != null ? lang : "zh");
            params.add("spd", "5");
            params.add("pit", "5");
            params.add("vol", "5");
            params.add("per", "0");
            params.add("aue", "3");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    speechConfig.getTtsUrl(), request, byte[].class);

            byte[] result = response.getBody();
            if (result != null && result.length > 0) {
                return result;
            } else {
                throw new RuntimeException("语音合成失败，返回空数据");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("语音合成异常", e);
            throw new RuntimeException("语音合成服务异常，请稍后重试");
        }
    }

    /**
     * 获取access_token（带缓存）
     *
     * @return access_token
     */
    public synchronized String getAccessToken() {
        if (cachedAccessToken != null
                && tokenExpireTime != null
                && LocalDateTime.now().isBefore(tokenExpireTime)) {
            return cachedAccessToken;
        }

        try {
            String url = speechConfig.getTokenUrl()
                    + "?grant_type=client_credentials"
                    + "&client_id=" + speechConfig.getApiKey()
                    + "&client_secret=" + speechConfig.getSecretKey();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject result = JSONUtil.parseObj(response.getBody());

            if (result.containsKey("access_token")) {
                cachedAccessToken = result.getStr("access_token");
                Integer expiresIn = result.getInt("expires_in", 2592000);
                tokenExpireTime = LocalDateTime.now().plusSeconds(expiresIn - 3600);
                log.info("获取百度语音access_token成功，有效期{}秒", expiresIn);
                return cachedAccessToken;
            } else {
                log.error("获取access_token失败: {}", response.getBody());
                throw new RuntimeException("获取语音服务令牌失败");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取access_token异常", e);
            throw new RuntimeException("获取语音服务令牌异常");
        }
    }
}
