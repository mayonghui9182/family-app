# 微信与网页语音输入支持分析

核对日期：2026-10-16。本轮只分析语音，未增加录音、识别接口或转写服务。

| 输入方式 | 当前支持 | 代码依据 |
|---|---|---|
| 公众号聊天中直接发语音气泡 | 不支持 | `wechat-service/app/routes/wechat_routes.py`仅允许text，其余消息返回TEXT_ONLY_HINT |
| 手机键盘听写后按文字发送 | 可走现有文字处理 | 服务收到的是text；仍由用户核对输入与任务清单 |
| 微信客户端自行转文字但未作为文字重新发送 | 不能据此保证服务器收到文字 | 服务按回调MsgType判断，不读取手机上显示的转写 |
| 微信内网页录音/语音按钮 | 未实现 | frontend/src无MediaRecorder/SpeechRecognition或微信JS-SDK语音调用 |
| 普通浏览器网页语音输入 | 未实现 | 没有录音上传、转写接口或对应组件 |

## 当前缺口

`wechat-service/app/wechat/inbound.py`的InboundMessage只保留文字/事件字段，未读取Recognition、MediaId、Format。回调中非text直接回复“目前我只能看懂文字，请直接打字发给我”，不会入会话队列。agent只接收user_text字符串，当前模型调用没有音频输入路径；不能认为改模型URL或API key就获得语音转写。

## 可行接入路径

1. 优先验证本公众号语音回调是否提供非空Recognition：如果具备该能力，可将转写作为文字复用现有MsgId幂等、绑定、family_id路由和可靠入队；转写失败应有明确下一步提示。
2. 没有Recognition时，需通过MediaId受控下载音频，再接语音转文字服务。音频处理应异步排队，避免阻塞微信回调；需要确认转写服务凭据、音频格式、语言、时长、超时与留存范围。现有文本模型配置不能替代此环节。
3. 网页需要单独增加录音权限、HTTPS下的录音/上传或微信JS-SDK签名接口，再接同一转写服务；不能只增加一个麦克风图标就称已支持。
4. 转写后展示用户可核对的文字与任务候选，沿用当前版本确认流程，避免识别错字直接变成任务。

## 依据和待验证项

- 本仓库回调分支、XML解析字段、Settings及前端录音/语音API精确搜索是“当前不支持”的直接依据。
- [wechatpy消息实现](https://docs.wechatpy.org/zh-cn/master/_modules/wechatpy/messages.html)的VoiceMessage包含Recognition/MediaId/Format，是可选转写字段路径的实现参考，不代表本测试号已开通。
- 官方[普通消息](https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Receiving_standard_messages.html)和[JS-SDK](https://developers.weixin.qq.com/doc/offiaccount/OA_Web_Apps/JS-SDK.html)页本轮工具无法读取；没有据此断言当前账号权限或资格。
- 尚未采集本号真实语音回调、未读取账户语音识别权限、未下载音频或验证ASR凭据。因此后续应先做本号语音能力探测，再决定Recognition优先或ASR方案。
