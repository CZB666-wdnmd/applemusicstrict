# 0.2.2 入口调整

- 移除 MainActivity 的 LAUNCHER category，保留 MAIN + MODULE_SETTINGS 和 exported Activity；不禁用组件，不干预 LSPosed 强制显示桌面入口。
- 在 Apple Music getAudioCategory 返回的全新音频分组里追加“强制选择音质”，使用宿主原生 Compose Action 模型及宿主加载器回调。
- 复制条目列表后追加，保留原生条目顺序与分组字段。入口独立于接管开关，点击显式打开模块 MD3 界面。
- 设置入口绑定或点击异常被隔离，保留 LSPosed 入口。对 getPreferenceItems 调用者反优化以避免内联跳过 hook。

2026-10-08 静态检查：1606、1607 均通过 50 个方法、48 个字段的精确接口核验；git diff --check 通过。
1606 使用工作目录保留的原始 base APK（哈希与已知 profile 一致），因为 Downloads 原始附件已不在原路径。

用户随后明确授权“编译好安装上去，然后我来测试”。已完成 assembleDebug、lintDebug（0 errors、5 warnings），签名校验通过且与旧版相同，adb install -r 成功。设备确认 versionName=0.2.2、versionCode=5。没有代替用户执行入口交互测试；重新启动 Apple Music 后由用户验证。
待验证：升级后默认桌面入口消失、LSPosed 模块设置可打开、音频分组显示且点击打开控制界面、返回 Apple Music 正常。
