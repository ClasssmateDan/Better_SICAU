# 课间课程表

Windows 离线课程表与静态网页，支持课程管理、JSON 导入导出、单双周、线上授课周次和上下课提醒。

**公开版首次启动为空课表，0 门课程、0 学分。** 学期名称、开始日期、周数和作息是可编辑的通用模板，请先打开“学期设置”修改，再添加课程或导入本人的 JSON。不内置个人课程、教师信息或签到 classid 参照，重置功能也只恢复空白模板。

## 下载与运行

Windows 10/11 x64 用户可下载 [Releases](https://github.com/ClasssmateDan/Better_SICAU/releases) 中的 `课间课程表-Windows-x64-1.7.3.zip`。完整解压到固定、可写的文件夹，运行 `课间课程表-1.7.3.exe`。系统需要 Microsoft Edge WebView2 Runtime；静态网页可以直接打开 `index.html`。

发布包直接附带 `课间数据/course-tools/browser-extension` 扩展目录和课程工具。扩展安装步骤见“课程工具使用说明.md”，加载整个扩展目录。普通课表不需要安装扩展。

程序数据保存在 EXE 旁的“课间数据”，包括课表、提醒设置、浏览器配置和课程工具记录。公开版不自动读取 AppData 中的旧版课表；首次在空目录运行时为空表。已有同目录数据会正常保留，可通过“导入课表”导入本人备份。移动或备份时请保留完整数据目录。

## 导入课表

从教务系统取得本人课表与作息，将材料和“课表数据格式说明.md”交给 AI 生成 JSON，再在程序中校验、预览并导入。“课表导入示例.json”是虚构格式示例，不会在启动时加载。`classid` 为可选字段，应从本人教学班页面核对，不按课程名称或他人编号推断。

## 课程工具

Windows 版保留独立的课程工具和浏览器扩展，可手动读取本人登录、签到及上传文件型作业或报告。学校相关功能面向四川农业大学 EOL，其他学校可使用普通课表功能。

请求头 IP 设置效果不稳定，不改变真实网络出口；报告重交不能通过该设置改变平台记录 IP。读取登录、签到和上传均需手动操作。详细步骤及功能限制见“课程工具使用说明.md”。

## 本地开发

静态网页构建不需要安装 npm 依赖，准备 Node.js 后运行：

```sh
npm test
npm run build
```

然后直接打开 `index.html`，无需启动端口服务。

Windows EXE 构建使用系统 .NET Framework 编译器、WebView2 SDK、Python 3.13 和项目内 PyInstaller 6.22.3：

```sh
python -m pip install --target desktop/.packaging pyinstaller==6.22.3
npm run prepare:exe
npm run build:exe
npm run test:exe
```

SDK 会从固定来源下载并核对 SHA256；首次准备依赖需要联网。构建产物位于 `release/`。第三方组件包括 WebView2、jsQR、qrcode-generator、CPython、Tcl/Tk 和 PyInstaller，其许可随相应源码或发布包提供。

## 上传到 GitHub

解压源码包，将内容上传到仓库根目录；Windows ZIP 可作为 GitHub Release 附件上传。`.gitignore` 已排除运行数据、HAR、浏览器配置、构建缓存和发布目录。请使用干净源码包发布，不将使用后的“课间数据”上传。
