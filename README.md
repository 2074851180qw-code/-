# 大肥鱼桌宠（Android）

一个住在 Android 桌面上的悬浮 Q 版桌宠：会走动、会饿、会困，能聊天、能查余额、能记住你说过的事。

原生 Kotlin 实现，不依赖任何第三方桌宠框架。

## 功能

**桌宠本体**
- 悬浮窗常驻桌面，独立前台服务保活
- 自己来回走、没人理会睡着、饿了会说话
- 单击摸头、双击喂饭、连点会生气、长按出菜单
- 拖拽拎起，松手落在原地（可关重力改成自由摆放）
- 大小 60%~220% 调节
- 精力 / 金币 / 经验等级
- 内置形象：Q 版女仆装（9 帧动画），可自行替换

**聊天**
- DeepSeek 流式输出，逐字蹦出
- 快捷回复条（摸摸头 / 饿了吗 / 今天好累 / 讲个笑话）
- 性格人设可编辑，可恢复默认
- 长期记忆：说「记住……」直接落库；每 6 轮自动提炼一次；可查看、删除、清空

**余额**
- 前台服务每 30 分钟查一次 `/user/balance`，显示在常驻通知
- 低于阈值（默认 ¥10）提醒

## 安装

1. 安装 `app-debug.apk`（或自行构建）
2. 依次授权：悬浮窗 → 通知（Android 13+）→ 忽略电池优化 → 自启动（国产 ROM 必做）
3. 填 DeepSeek API Key（加密存储，不写日志）
4. 点「启动桌宠」

需要自备 [DeepSeek](https://platform.deepseek.com/) API Key。

## 构建

需要 JDK 17+ 和 Android SDK（platform 34 / build-tools 34），推荐直接用 Android Studio 打开本目录。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- 找不到 SDK：在根目录建 `local.properties`，写 `sdk.dir=/你的/Android/sdk`
- 国内网络慢：`settings.gradle.kts` 里有注释掉的阿里云镜像，取消注释即可
- `gradle-wrapper.jar` 是二进制，若缺失，用 Android Studio 打开会自动下载

## 换成你自己的形象

内置形象是 `maid_*` 这套（9 张）。想替换，把透明 PNG 放进
`app/src/main/res/drawable-nodpi/`，按同样命名：

```
<装扮名>_idle_0.png  _idle_1.png      待机（至少给这一张）
<装扮名>_walk_0.png  _walk_1.png      走路
<装扮名>_sleep_0.png                  睡觉
<装扮名>_eat_0.png                    吃饭 / 说话
<装扮名>_happy_0.png _drag_0.png _alert_0.png
```

- 缺哪个动画就退回 idle；连 idle 也没有就画一条蓝色占位鱼
- 建议高度 700px、底部对齐、默认朝左（向右走时代码自动镜像）
- 想加多套装扮：在 `PetController.kt` 的 `outfits` 列表里登记第二套，菜单会自动出现「换个装扮」

## 目录

| 文件 | 职责 |
|---|---|
| `PetService.kt` | 前台服务：持有悬浮窗、定时查余额、常驻通知 |
| `PetController.kt` | 窗口管理 + 状态机 + 触摸 + 菜单 + 聊天面板（主要改这里） |
| `PetView.kt` / `FishPainter.kt` | 绘制：精灵帧、比例适配、呼吸/挤压 |
| `DeepSeekApi.kt` | `/user/balance` 与 `/chat/completions`（SSE 流式） |
| `PetPrefs.kt` | 普通偏好：饥饿、心情、性格、记忆、装扮、大小、精力、金币 |
| `SecureStore.kt` | API Key 加密存储（EncryptedSharedPreferences） |
| `MainActivity.kt` | 授权引导 + 设置页 |
| `OemSettings.kt` | 各 ROM 自启动页面跳转 |
| `BootReceiver.kt` | 开机自启 |

## 实现要点

- 悬浮窗：`SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`，配前台服务保活；Android 12+ 不允许后台启动前台服务，因此只从界面启动
- 点击穿透：宠物窗口尺寸贴合精灵比例，窗口外天然可点到桌面，另加 `FLAG_NOT_TOUCH_MODAL`；气泡窗口 `FLAG_NOT_TOUCHABLE`
- API Key：`EncryptedSharedPreferences`，只在后台协程里读取，不写日志，工程内无 WebView
- 聊天：`stream=true`，逐行解析 SSE 的 `data:` 行

## 版本

见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

代码采用 Apache License 2.0，见 [LICENSE](LICENSE)。

第三方依赖见 [NOTICE](NOTICE)；角色素材来源与使用提醒见 [素材说明.md](素材说明.md)。

本项目与 DeepSeek 官方无关，仅调用其公开 API。
