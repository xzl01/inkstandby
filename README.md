# 墨屏待机（InkStandby）

给讯飞 XF-R3（起点阅读器）用的**锁屏/待机图设置 App**：支持三种内容源，在**按下电源键退出待机（亮屏）时更新一次**，不做频繁轮询，省电。

## 功能

- 内容源（三选一）：
  1. **本地图片文件**（App 内也可直接「选择图片文件」导入）
  2. **网络图片 URL**（亮屏时下载，失败自动沿用上次缓存）
  3. **文字模板**（按 e-ink 风格渲染成 1264×1680 图，支持 `{date}` `{time}` `{weekday}` `{battery}` 变量）
- 输出竖屏 1264×1680 + 横屏 1680×1264，同时覆盖 **待机 / 充电 / 低电量** 三种状态
- 触发时机：**亮屏（SCREEN_ON / USER_PRESENT）** 时更新一次；开机时更新；App 内「立即应用」手动更新
- 低优先级常驻通知的前台服务，不亮屏时完全不工作

## 原理

XF-R3 的「锁屏壁纸」实际是 e-ink 待机图，由 system_server 里的 `IFinkWallpaper` 持有：

- 图片文件：`/data/local/iflytek/wallpaper/standby*.png`、`sleep_rotation_*.png`
- 只改文件不够，系统内存里有缓存；需要同时调用 **`android.iflyink.IFinkManager.setWallpaper(which, rotation, bitmap)`**（`ifink` binder 服务）实时刷新
- 本 App 通过反射调用（本机是 userdebug、`ro.debuggable=1`，隐藏 API 可用）：
  1. `android.util.EinkUtil.setWallpaper(...)` 落盘
  2. `android.iflyink.IFinkManager.setWallpaper(...)` 刷新系统显示
- `which`：`0`=待机、`4`=充电、`5`=低电量

## 关键前提（本机已做）

App 走的是 `ifink` 服务的实时通道（由系统进程写入待机图），**普通 App 权限即可，不需要 root、也不需要 chmod 目录**。
（`/data/local/iflytek/wallpaper` 的权限在重启后会被系统重置回 770，App 会跳过直接写文件，仅用 `IFinkManager` 通道。）

需要禁用厂商会覆盖待机图的生成器 / 起点 App：

```bash
adb shell pm disable-user com.qidian.eink
adb shell pm disable com.iflytek.ainote.settings/.service.ScheduleSleepWallPaperCreateService
adb shell pm disable com.iflytek.ainote.settings/.receiver.WallpaperReceiver
```

> 不执行这些也能用，但厂商每天/每次休眠可能把图换回「讯飞有声书 / 起点日历广告」。
> 本机已禁用；如需恢复起点读书：`adb shell pm enable com.qidian.eink`。

## 构建与安装

```bash
# 在仓库根目录
bash scripts/dev-gradle.sh -p tools/inkstandby :app:assembleRelease
adb install -r tools/inkstandby/app/build/outputs/apk/release/app-release.apk
```

安装后**手动打开一次**（解除 Android 的 stopped 状态，否则收不到 BOOT_COMPLETED）。

## 回滚

```bash
adb shell pm enable com.qidian.eink
adb shell pm enable com.iflytek.ainote.settings/.service.ScheduleSleepWallPaperCreateService
adb shell pm enable com.iflytek.ainote.settings/.receiver.WallpaperReceiver
# 恢复原厂待机图（如果本地还有备份）
adb push .tooling/standby/orig/. /data/local/iflytek/wallpaper/
```

## 已知限制

- 依赖 userdebug 构建的隐藏 API；正式 user 版本固件上反射可能被拦截。
- 目录权限、SELinux 为 Permissive 是本机能工作的前提；换机/刷机后需重新做一次性准备。
- 亮屏后约 1~2 秒完成（实测：`3/3 写入成功（1.1s）`），建议唤醒后稍等再休眠。
- 已验证重启后 App 会通过 `BOOT_COMPLETED` 自动启动并更新；只要安装后手动打开过一次。
