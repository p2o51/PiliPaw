# PiliPaw 0.5.7 性能与稳定性检查

2026 年 10 月 23 日，基线为 `p2o51/PiliPaw` 的 `main` 提交 `efd8abc544099b0a51a787d59776597dff555fe5`，versionCode 57。GitHub 与本地基线已核对。修复位于本地分支 `codex/performance-audit-0.5.7`，版本号保持 0.5.7。

本次对启动、首页列表、Bilibili 与 YouTube 播放、歌词和封面、返回与迷你播放器、直播弹幕、离线缓存、历史记录和云同步做了热点扫描、重点路径代码审查、构建检查与 Pixel Tablet 验证。已经修复下列具体问题。实测改善集中在连续打断的封面动画，首页滑动表现基本不变。

## 修复内容

| 问题 | 修复与作用 | 验证 |
| --- | --- | --- |
| 快速切歌递归保留 InterruptedCoverPainter，每层继续绘制旧封面和半色调路径 | 将中断画面压成一张快照，新的动画只保留底图和当前封面；加载失败继续显示已有画面 | 原版与修复版同机执行 30 次切换，抽样检查没有露出背景且最终显示最新封面 |
| YouTube 每 5 秒在主线程编码并写入整份历史 JSON；写盘异常向 MainScope 传播 | 单个后台线程串行编码和写盘，同一路径待写快照合并；检查替换结果，失败保留旧文件并记录错误 | 4 项文件写入回归：延迟编码、最后值获胜、写入期间的新值、异常后继续工作和替换失败 |
| 云同步在主线程逐个读写歌词文件 | 文件导入导出切到 IO dispatcher，Compose 缓存修改仍回到调用方主线程 | 最终编译和单元测试；未主动触发账号云同步 |
| SurfaceView 截图取消后结果没有正确回收；直播返回截图超时会提前回收仍由 PixelCopy 写入的 Bitmap | 将位图所有权保留到 PixelCopy 回调，再按成功交付、失败或取消回收 | 4 项真机回归覆盖成功、失败、请求被拒与取消后的延迟回调 |
| YouTube 多次选清晰度的旧任务可能覆盖新选择，关闭后跳过片段的循环仍在轮询 | 管理并取消 qualityJob；关闭时清理字幕、片段轮询和轨道状态 | 编译、YouTube 真机播放与迷你播放器回归；极慢解析竞态由取消边界代码审查覆盖 |
| 直播零长度包使解析无法前进；损坏包长与嵌套压缩缺少边界 | 验证最小头长、剩余长度，限制解压输出 16 MiB 和压缩嵌套深度 4 | 5 项 LivePackets 回归，包括零长度超时保护、畸形长度、嵌套与超限 |
| 弹幕解压没有输出上限，异常退出不保证释放 Inflater | 共用有界解压器，要求结束或报错，并在 finally 释放原生资源 | 4 项解压回归，覆盖 raw/zlib、空压缩流、截断和超限 |
| 直播关闭后的迟到回调仍可启动心跳或处理消息 | closed 跨线程可见，onOpen/onMessage 和心跳循环检查关闭状态 | 代码审查、构建 |
| Android 8/8.1 调用 Android 9 才有的图片解码与 MediaExtractor.sampleSize API | API 26/27 使用 GifDecoder 与轨道声明的输入缓冲大小；保留 API 28+ 路径 | Lint 的 NewApi 错误由 4 项降至 0，其中另外 2 项为已有触觉版本守卫补充可识别的注解；未在 API 26/27 真机运行 |
| 歌词滚动和标题跑马灯每帧重建边缘渐变对象 | drawWithCache 按尺寸缓存渐变，保留原来的遮罩和视觉效果 | 编译与实际听歌页面检查；未单独量化歌词长列表收益 |

## 性能测量

设备为用户授权使用的 Pixel Tablet，Android 17，正式 UI 滑动使用原生 1600 × 2560、density 320。没有清除应用数据或执行 connectedAndroidTest。首页测量期间没有录屏，模式切换录屏单独采集。

| 场景与指标 | 原版 | 修复版 |
| --- | ---: | ---: |
| 封面连续切换 30 次，FrameMetrics P95 | 52.83 ms | 40.91 ms |
| 封面压力测试采集帧数 | 149 | 190 |
| 首页 12 次往返滑动，gfxinfo P95 | 26 ms | 26 ms |
| 首页 gfxinfo 卡顿帧 | 2 / 1102，0.18% | 2 / 1105，0.18% |
| 首页 gfxinfo P99 | 28 ms | 30 ms |

封面数字来自同一份 instrumentation 场景、同机各一次 debug 构建测试：360dp 封面、3 张本地纯色图片、每 90ms 请求一次切换，并在过程抽样检查像素。P95 降低约 22.6%，但仍超过 60 Hz 的单帧预算；该压力结果不能代表整个 App 的流畅度或正常播放帧率。首页内容由网络推荐动态变化，结果用于检查明显回退，不将内存快照差异归因于修复。

## 构建与真机结果

- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleRelease`、`:app:lintDebug` 均完成。
- JVM 共 91 项：89 项通过，2 项按原配置跳过的网络探针，0 失败。相较基线新增 13 项。
- PerformanceRegressionTest 的 5 项真机测试全部通过。
- MiniBarPinchTest 通过，截图确认 YouTube 内容正在迷你浮窗中显示。
- 旧 PinchToListenTest 返回通过，但其默认视频未加载，不能据此认定播放正常；随后在 release 上从首页打开实际可播放的 ILLIT 视频，确认进度前进、封面显示，以及视频与仅听声音之间的转换。录屏留存供复核。
- 本轮读取的 crash buffer 与应用退出记录没有发现 PiliPaw 的 Java/native crash 或 ANR；退出记录包含覆盖安装和 instrumentation 结束导致的正常停止。
- 最终 release 使用 Android Debug 证书，versionName 0.5.7、versionCode 57，已在 Pixel Tablet 覆盖安装并保留数据；`am start -W` 返回 `Status: ok`、`LaunchState: COLD`，本次启动 512 ms（单次交付确认，不用于性能比较）。

Lint 任务的构建成功不等于零诊断：项目原本设置了 `abortOnError = false`。本次从 294 条 error 级诊断降到 290 条，剩余主要是 Media3 实验 API 声明（277 条），以及 WrongConstant、RememberReturnType、NoCollectCallFound、StateFlowValueCalledInComposition 和缩进诊断。没有将这些历史诊断批量屏蔽。

## 交付与覆盖边界

安装包与本机证据存放在 `build/performance-audit-0.5.7/`（构建产物目录，不入 Git）。主要文件：

- `PiliPaw-0.5.7-performance-release-debug-signed.apk`
- `baseline-cover-metrics.txt`、`fixed-cover-metrics.txt`
- `baseline-scroll.txt`、`fixed-scroll.txt`
- `fixed-device-test.txt`、`fixed-minibar-test.txt`
- `listen-transition.mp4`、`listen-return.mp4`
- `release-listen2.png`、`minibar-after.png`
- `final-validation.log`、`lint-results-debug.txt`
- `delivered-launch.txt`、`final-crash-buffer.txt`、`final-exit-info.txt`

此次验证不等同于多机型兼容认证：没有 Pixel 11 Pro、Android 8/8.1 实机、数小时后台播放、所有直播房间和下载格式的全量覆盖。剩余明显性能方向是半色调遮罩自身的长帧，以及大规模本地库的启动读取；需要独立持续测量，不能从本次短时结果推断完全没有 OOM 或耗电问题。

渐变缓存实现遵循 [Android Compose 绘图修饰符文档](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)，通过缓存 Brush 等对象减少重复分配。
