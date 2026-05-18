# Android Studio 编译运行说明

本目录 `android_app/` 就是 Android Studio 项目根目录。请用 Android Studio 直接打开这个目录，不要打开外层后端项目目录。

## 1. 打开项目

1. 启动 Android Studio。
2. 选择 `Open`。
3. 选择：
   `D:\AI辅助出行系统\android_app`
4. 等待 Gradle Sync 完成。

如果 Android Studio 提示安装 SDK、Build Tools 或 Gradle，点击 `Install` / `Sync Now` 即可。

## 2. 检查 SDK

项目当前使用：

- `compileSdk = 35`
- `minSdk = 26`
- Android Gradle Plugin `8.9.0`
- Kotlin `2.0.21`
- Java 17

如果 Sync 报 SDK 路径错误，打开：

`android_app/local.properties`

确认内容类似：

```properties
sdk.dir=C\:\\Users\\余家铮\\AppData\\Local\\Android\\Sdk
```

如果你的 Android SDK 安装在其他位置，请在 Android Studio 中打开：

`File -> Project Structure -> SDK Location`

选择正确的 Android SDK 目录，Android Studio 会自动更新 `local.properties`。

## 3. 运行到手机

1. 手机打开开发者选项。
2. 开启 USB 调试。
3. 用 USB 连接电脑。
4. Android Studio 顶部设备栏选择你的手机。
5. 点击绿色运行按钮 `Run app`。

运行成功后，手机上安装的就是当前源码编译出来的新版本 App。

## 4. 生成 APK

在 Android Studio 顶部菜单选择：

`Build -> Build Bundle(s) / APK(s) -> Build APK(s)`

生成完成后，点击右下角提示里的 `locate`，APK 通常在：

`android_app\app\build\outputs\apk\debug\app-debug.apk`

## 5. App 连接后端

1. 先启动后端：

```bash
python app_main.py
```

2. 确认电脑和手机在同一个局域网。
3. 在电脑命令行查看 IP，例如：

```powershell
ipconfig
```

4. 打开手机 App，点击右上角设置。
5. Host 填电脑局域网 IP，例如 `192.168.1.23`。
6. Port 填 `8081`。
7. 保存并连接。

不要在真机上使用 `10.0.2.2`，它只适用于 Android 模拟器访问电脑。

## 6. 验证当前功能

连接成功后测试：

1. 对手机说：“你好，你能听到我说话吗？”
2. 后端应打印 ASR final 文本。
3. 手机界面应显示 AI 回复。
4. 手机应播放后端通过 `/stream.wav` 推送的阿里语音。
5. 再说：“开始导航。”确认原有导航功能仍能启动。

如果手机连不上服务器，优先检查：

- 后端是否已启动并监听 `0.0.0.0:8081`。
- 手机和电脑是否在同一个 Wi-Fi。
- App 设置里的 Host 是否是电脑局域网 IP。
- Windows 防火墙是否放行 Python 或 8081 端口。
