# 0.2.2 入口调整

- 移除 MainActivity 的 LAUNCHER category，保留 MAIN + MODULE_SETTINGS 和 exported Activity；不禁用组件，不干预 LSPosed 强制显示桌面入口。
- 在 Apple Music getAudioCategory 返回的全新音频分组里追加“强制选择音质”，使用宿主原生 Compose Action 模型及宿主加载器回调。
- 复制条目列表后追加，保留原生条目顺序与分组字段。入口独立于接管开关，点击显式打开模块 MD3 界面。
- 设置入口绑定或点击异常被隔离，保留 LSPosed 入口。对 getPreferenceItems 调用者反优化以避免内联跳过 hook。

2026-10-08 静态检查：1606、1607 均通过 50 个方法、48 个字段的精确接口核验；git diff --check 通过。
1606 使用工作目录保留的原始 base APK（哈希与已知 profile 一致），因为 Downloads 原始附件已不在原路径。

未执行本地构建、安装或设备入口测试；此前授权针对 0.2.1，本次没有新增构建授权。手机当前仍为 0.2.1。
待验证：升级后默认桌面入口消失、LSPosed 模块设置可打开、音频分组显示且点击打开控制界面、返回 Apple Music 正常。
