# Better SICAU · 课间课程表

Windows 与 Android 离线课程表，支持课程管理、JSON 导入导出、单双周、日历备忘与提醒，安卓版内置学习通签到。

**公开版首次启动为空课表，0 门课程、0 学分。** 学期名称、开始日期、周数和作息是可编辑的通用模板，请先打开“学期设置”修改，再添加课程或导入本人的 JSON。不内置个人课程、教师信息或签到 classid 参照，重置功能也只恢复空白模板。

## 下载与运行

Windows 10/11 x64 用户可从 [Releases](https://github.com/ClasssmateDan/Better_SICAU/releases) 下载 **Better SICAU 桌面版**（`Better-SICAU-Desktop-Windows-x64-1.9.1.zip`）。完整解压到固定、可写的文件夹，运行 `课间课程表-1.9.1.exe`。系统需要 Microsoft Edge WebView2 Runtime；静态网页可以直接打开 `index.html`。

Android 8.0 及以上用户可下载 **Better SICAU 安卓版**（`Better-SICAU-Android-1.9.2.apk`）。首次安装为空课表，覆盖升级保留已有数据。底边栏“学习通”提供账号管理、并行活动查询、扫码／选图／粘贴和顺序签到，不申请位置权限或采集本机位置。账号会话单独加密保存，不包含在课表 JSON 中；首次使用需登录本人账号。签到结果与日历中的手动签到标记分别保存。

程序数据保存在 EXE 旁的“课间数据”，包括课表、提醒设置、浏览器配置。公开版不自动读取 AppData 中的旧版课表；首次在空目录运行时为空表。已有同目录数据会正常保留，可通过“导入课表”导入本人备份。移动或备份时请保留完整数据目录。

## 导入课表

从教务系统取得本人课表与作息，将材料和“课表数据格式说明.md”交给 AI 生成 JSON，再在程序中校验、预览并导入。“课表导入示例.json”是虚构格式示例，不会在启动时加载。`classid` 为可选字段，应从本人教学班页面核对，不按课程名称或他人编号推断。

## 日历备忘

在日历中选择日期，可添加备忘录、指定北京时间提醒，或对当天课程标记及撤销本地签到。提醒需开启系统通知总开关；Windows 程序需保持运行或驻留托盘。历史签到在修改或删除课程后仍保留。日历数据与课表一并导入导出；导入为整体替换，旧文件不含 calendar 时将清空日历，请先导出备份。新安装的公开版日历为空。

## 本地开发

静态网页构建不需要安装 npm 依赖，准备 Node.js 后运行：

```sh
npm test
npm run build
```

然后直接打开 `index.html`，无需启动端口服务。

Windows EXE 构建使用系统 .NET Framework 编译器、WebView2 SDK：

```sh
npm run prepare:exe
npm run build:exe
npm run test:exe
```

SDK 会从固定来源下载并核对 SHA256；首次准备依赖需要联网。构建产物位于 `release/`。第三方组件包括 WebView2、jsQR、qrcode-generator、ZXing 与 jsoup，其许可随相应源码或发布包提供。

安卓构建需 JDK 17/21、Android SDK platform 35 与 build-tools 35.0.0，配置 `JAVA_HOME` 和 `KEJIAN_ANDROID_SDK` 后运行 `npm run build:apk`。首次本地构建会生成私有签名密钥，后续更新需保留相同密钥；不要将密钥或密码上传仓库。`KEJIAN_ANDROID_SIGNING_DIR` 可指定已有签名目录。

学习通模块的离线测试使用合成账号和模拟响应，不请求真实学校接口。运行 `npm run test:learningpass` 需要 Gradle 8.9 及已缓存的 Robolectric 4.17；通过 `KEJIAN_GRADLE_HOME`、`KEJIAN_GRADLE_USER_HOME` 配置，Windows 测试暂存目录须为英文路径。公开包已完成编译、签名和模拟测试，尚未进行安卓真机复测。

## 上传到 GitHub

解压源码包，将内容上传到仓库根目录；Windows ZIP 可作为 GitHub Release 附件上传。`.gitignore` 已排除运行数据、HAR、浏览器配置、构建缓存和发布目录。请使用干净源码包发布，不将使用后的“课间数据”上传。

课程可设置自定义“签到方式”，默认为教务系统。其他方式点击“跳转签到页”时需确认是否继续；旧课表缺少字段时采用默认方式，导入导出会保留该设置。

关闭 Windows 主窗口会静默收起到托盘，与提醒开关无关。双击托盘图标恢复，右键“退出课间”结束程序。“我的课表”随窗口可用宽高调整行列与文字大小。

新版手机端支持左右滑动课表切周、顶部周末开关；“设置”显示版本号，PC 左下角显示桌面版本号。两端内置 2026 年官方放假调休与 2027 年节日日期，系统提醒开启后可接收当天 09:00 的节假日通知；2027 年调休安排待更新，课程不会自动移动。
