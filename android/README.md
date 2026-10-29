# Better SICAU 安卓版 1.5.3

首次安装为空课表，支持 JSON 导入导出、签到方式配置与上下课提醒。系统要求 Android 8.0 及以上。覆盖安装会保留原有应用数据，更新使用相同签名密钥。

1.5.3 修复重复打开签到页时重复初始化 WebView 的异常。已进行模型、生命周期及手机界面模拟验证，未进行真机复测。

开发需要 JDK 17/21、Android SDK platform 35 和 build-tools 35.0.0，配置 JAVA_HOME、KEJIAN_ANDROID_SDK 后运行 npm run build:apk。签名目录和本机工具链配置不可上传仓库。
