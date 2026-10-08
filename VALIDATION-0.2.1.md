# 0.2.1 验证记录

2026-10-08，Apple Music 7.0.0-beta/1606，LSPosed IT 2.2.1 (7919)。

## 已完成的设备回归

本轮已安装的 0.2.0 中间修正版，测试曲目《有精卵<You Say Run> -JAPAN MIX-》：

- 修正无候选渲染器处理后，启用接管可以播放，出现 MASTER/SELECT 回报。
- 取消在线源的 dedicateDownloadedPlaylist 限定后，同一歌曲清单恢复 4 个候选：ALAC、AAC 标称 256/128/64 kbps。
- 界面选择 AAC 256 并应用后，播放器报告 AAC；媒体会话保持 PLAYING、speed=1.0、error=null。
- 界面中的当前音质和清单不是同一个值：清单声明 ALAC 16 bit，解码器输入/宿主回报为 24 bit。
- 已验证标准 URI 授权打通 Provider，普通 shell 调用被 UID 校验拒绝。

## 本地检查

- ControlSmoke 330 项策略、音轨身份、清单保留和内容范围断言通过。
- 1606、1607 最终接口静态核验均通过：各 47 个方法、46 个字段。
- 0.2.1 assembleDebug、lintDebug 最终通过：0 errors、5 warnings。LinearLayout 常量错误已修复。
- 同次 lint 还有依赖更新、导出 Provider、备份规则、图标和字符串提示。Provider 通过代码校验调用 UID，并未对任意应用开放配置修改。

## 最终 APK 设备验证

0.2.1 的最新源码另修正了将解码后 PCM 1411 kbps 当作 AAC 音源码率的显示错误，改读解码器输入 Format，并为 AAC 显示清单中的标称档位。
用户再次明确授权后，构建、同签名覆盖安装和 Apple Music 重启均已完成。签名 SHA-256：4038f9a176eb457045d93125673b0fe5dfb8f9eea40aae5c948a349bbc24493b。

最终 0.2.1 在《My Hero Academia -JAPAN MIX-》验证：

- Provider 在移除 forceQueryable 后仍正常通信，界面显示当前播放和 4 个真实音源版本。
- 自动最高音质：实际播放 ALAC 24 bit / 44.1 kHz；清单声明 16 bit，两者分别展示。
- 手动选择 AAC 128 并应用：实际显示 AAC 128 档、44.1 kHz、135 kbps，媒体会话 PLAYING、speed=1.0、error=null。
- 切回自动最高音质：恢复 ALAC，自动选项勾选正确，媒体会话持续 PLAYING、error=null；观察进度从 141202 ms 继续到 174563 ms，没有重置到起点。
- 最后保留接管开启、自动最高音质、优先杜比开启。测试曲目没有杜比候选，因此本次不能确认杜比切换的完整行为。

1607 只做静态核验；未做完整网络切换、长时间播放、不同输出设备或所有杜比曲目的覆盖测试。
