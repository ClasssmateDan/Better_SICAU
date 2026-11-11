# Better SICAU 安卓版 1.9.4

首次安装为空课表，支持 JSON 导入导出、二维码预测、日历备忘与提醒。系统要求 Android 8.0 及以上。覆盖安装保留原有应用数据，更新使用相同签名密钥。

底边栏“学习通”提供独立加密账号、活动准备、扫码／选图／粘贴及顺序签到。不申请位置权限或读取本机位置，首次打开不自动提交，来自独立版的账号需要重新登录。课表 JSON 不包含登录会话。教务签到窗口另提供默认关闭的 IPv4 兼容试验选项及网络诊断。

开发需要 JDK 17/21、Android SDK platform 35 和 build-tools 35.0.0，配置 JAVA_HOME、KEJIAN_ANDROID_SDK 后运行 npm run build:apk。ZXing 与 jsoup 位于 android/vendor/learningpass，并在构建时校验哈希；许可位于 android/learningpass。签名目录和本机工具链配置不可上传仓库。
