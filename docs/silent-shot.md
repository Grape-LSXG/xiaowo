# 单端静默截屏

## 需求

接单端（张老师）点「📸 看猫猫的屏幕」，点单端（猫猫）那边**什么都不用做**，
直接截一张当前屏幕发过去。

关键词是「不用做」：不弹权限框、不弹录屏确认、不用切回 App。

## 为什么原来的截图「貌似不可用」

v3.6 用的是 `view.draw(Canvas)` —— 截点单端自己的 View 树。它有两个硬伤：

| 场景 | 结果 |
|---|---|
| 点单端在后台 / 刚被系统重建 | `getWidth()` 为 0 → 返回 null → 对方只看到「截图失败」 |
| 点单端正常在前台 | 能截到，但**只有 App 自己的界面**：没有状态栏、没有对话框、没有系统弹层 |

而且它根本做不到「让对方看我在干什么」——用户想看的往往是整个屏幕。

另外还有一个更隐蔽的 bug（已修）：**接单端压根发不出截图请求**。
`RecvScreen.requestShot()` 只读 `peer_host`，而接单端只有在「拉菜谱」
成功时才会写入这个键。没拉过菜谱 = `peer_host` 为空 = 点了按钮什么都不发，
用户看到的就是「没反应」。

## 现在怎么做：无障碍 takeScreenshot()

Android 11 (API 30) 给 `AccessibilityService` 加了 `takeScreenshot()`：

```java
takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback);
// callback.onSuccess(ScreenshotResult) → getHardwareBuffer() → Bitmap
```

**没有任何确认弹窗。** 授权只做一次：用户在系统设置 → 无障碍里勾选本服务，
之后永久有效。

### 三级降级

`Shot.captureBase64(context, fallbackRoot)`：

| 级别 | 手段 | 条件 | 说明 |
|---|---|---|---|
| ① | 无障碍 `takeScreenshot()` | API 30+ 且服务已开 | 整屏真截图，静默 |
| ② | `view.draw(Canvas)` | 总是可用 | 只截自己界面，后台可能失败 |
| ③ | 返回 null | — | 调用方明确报「截图失败」，不装死 |

## 实现要点（踩过的坑）

**1. `Bitmap.wrapHardwareBuffer()` 给的是 HardwareBitmap。**
只能用 GPU 绘制，`compress()` / `createScaledBitmap()` 在部分机型直接抛
`IllegalArgumentException`。所以拿到后立刻 `copy(ARGB_8888, false)` 转软件位图。

**2. 回调是异步的，必须自己等。**
`Shot.Result` 里放一个 `CountDownLatch`，`ShotService` 回调里 `countDown()`，
`Shot` 这边 `await(3000ms)`。超时就降级到 ②，不要卡死 HTTP 线程。

**3. 不能在主线程等。**
无障碍回调要几十到几百毫秒。原来 OrderScreen 的 HTTP handler 里
先 `runOnUiThread` 再 `latch.await` —— 那是把主线程自己等死（死锁风险）。
现在直接在服务器的工作线程上截。

**4. `canTakeScreenshot="true"` 必须写在 metadata XML 里。**
少这一行，`takeScreenshot()` 直接抛 `SecurityException`。
配置在 `res/xml/shot_service.xml`，清单里用 `<meta-data>` 指向它。

**5. 刻意不开 `canRetrieveWindowContent`。**
截屏不需要读取界面内容。权限越小越好——用户在无障碍列表里看到
「读取屏幕内容」会更犹豫。

## 文件

| 文件 | 作用 |
|---|---|
| `core/ShotService.java` | AccessibilityService 本体 + 状态检测 + 跳设置页 |
| `res/xml/shot_service.xml` | `canTakeScreenshot="true"` 配置 |
| `core/Shot.java` | 三级降级入口 |
| `ui/OrderScreen.java` | `shotForPeer()`：HTTP `/shot` 和中转 `TYPE_SHOT` 都走它 |
| `ui/RecvScreen.java` | `requestShot()`：三条路依次试（peer → 订单 replyHost → 中转） |
| `ui/PermissionGate.java` | 启动时强制拦截，没开就不让进 |
| `ui/KeepAliveCard.java` | 卡片上显示实时状态 + 补权限入口 |

## 真机验证（vivo V2502A / Android 17）

装好 v3.7.2、在系统设置里勾上无障碍后：

```
POST http://127.0.0.1:46177/shot
→ http=200  size=52441  time=0.048s
→ type=shotImg  img 39283 bytes
```

截出来的图是 **324×720 的完整屏幕**：状态栏（时间 15:12、电量 87%）、
底部导航栏、甚至连第三方悬浮窗都在画面里 —— 确认是系统级整屏截图，
不是 App 自己的 View 树。全程点单端没有任何弹窗或操作。

## 为什么不用 MediaProjection

| | 无障碍 | MediaProjection |
|---|---|---|
| 每次截屏要用户确认 | **否** | 是（点「开始录制」） |
| 需要常驻前台服务 | 否 | 是 |
| 授权次数 | 一次 | 每次会话 |
| 能截整屏 | 是 | 是 |

「静默」这个需求只有无障碍能满足。

## 局限

- **API 30 以下没有这个能力**，只能用 ② 兜底（`PermissionGate` 在老系统上
  也不会拿它拦人）。
- 某些 ROM（尤其国产深度定制）可能拒绝给侧载应用开无障碍。这种情况
  用户的 App 会卡在启动引导上——这是刻意的取舍：宁可让用户配齐再进，
  也不要留一个"看起来有但实际是坏的"功能。
