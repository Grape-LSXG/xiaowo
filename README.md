# 小窝 · 情侣点单 App

一个"假装是外卖 App"的情侣互动小应用：左边分类、右边撒娇菜单、点菜下单，
下单成功先在自己手机上炸一朵爱心，再通过网络送到对方的手机里，对方那边也炸一朵。

- **点单端** `小窝·点单` (`com.couple.xiaowo.order`)
- **接单端** `小窝·接单` (`com.couple.xiaowo.recv`)

两个 App **共用同一份源码**，只在编译时用常量 `BuildInfo.IS_ORDER` 区分角色。
APK：点单端约 660 KB、接单端约 5.2 MB（含官方 frpc）。不依赖任何第三方库（连 AndroidX 都没有）。

---

## 一、你最关心的问题：两个 App 怎么"凭空"通信？

通信没有魔法，只有一句话：**一个 App 开端口当服务器，另一个 App 主动去连它。**

手机是台完整的 Linux 机器，只要有 Wi-Fi/IP 网络，App 就能监听 TCP 端口、
也能主动发 HTTP 请求。两个 App 分别在两台手机上，就形成了"客户端 ↔ 服务端"。

### 1. 整体链路

```
┌──────────────── 手机 A（TA 的） ────────────────┐      ┌──────────────── 手机 B（你的） ────────────────┐
│  小窝·点单  OrderScreen                          │      │  小窝·接单  RecvScreen                        │
│                                                 │      │                                               │
│  点「下单」                                      │      │  Net.Server 监听 TCP 8765                      │
│      │                                          │      │        ▲                                      │
│      ▼                                          │      │        │ 解析 JSON                             │
│  Net.post("/order", 订单JSON) ──────────────────┼─TCP──┼────────┘                                      │
│      │                                          │ 8765 │        │  收到 → 全屏爱心 + 震动 + 卡片入列表   │
│      │ 收到 {"ok":true}                          │      │        ▼                                      │
│      ▼                                          │      │  点「回个爱心」                                │
│  清空购物车 + 再炸一次爱心                        │      │  Net.post("/heart") ───TCP──┐                  │
│  Net.Server 随机端口 ◀──────────────────────────┼──────┼─────────────────────────────┘                  │
└─────────────────────────────────────────────────┘      └───────────────────────────────────────────────┘
        │                                                                        ▲
        └──────────── UDP 8766 广播：「我是点单端，谁接单？」───────────────▶ 接单端应答「我在，端口 8765」
```

### 2. 为什么还要 UDP 广播？

光有 IP 还不够——你不知道对方手机当前是 `192.168.1.7` 还是 `192.168.1.23`。
所以点单端先往局域网广播地址（`255.255.255.255` + 各网卡广播地址 + `127.0.0.1`）的
**UDP 8766** 端口喊一嗓子：

```
发送：{"type":"discover","role":"order","name":"小窝·点单"}
接单端的 DiscoveryResponder 收到后回：
      {"type":"here","role":"recv","name":"小窝·接单","port":8765}
```

点单端拿到回包的**来源 IP** 和**端口**，就知道该往哪发订单了——零配置。

> 顺带一说：`127.0.0.1` 也放进广播目标里，所以**两个 App 装在同一台手机上**
> 也能自动找到对方，方便你现在就自测。

### 3. 如果两台手机不在同一个网络？

局域网方案只在同一 Wi-Fi 下有效。跨网络时**不用改一行代码**，把"对方的地址"
填成一个能路由到的 IP 就行，常见三种做法：

| 做法 | 操作 | 适合 |
|---|---|---|
| **Tailscale / ZeroTier**（最推荐） | 两台手机装同一个虚拟局域网 App，登录同一账号，然后在点单端「连接」里填接单端显示的 `100.x.x.x:8765` | 异地情侣，零服务器成本 |
| 路由器端口转发 | 家里路由器把 8765 转发到接单端手机的内网 IP，填公网 IP | 有公网 IP、懂网络 |
| 自建云中继 | 在 VPS 上跑个反向代理/转发，把 `/order` 转到接单端 | 想做成正式产品时 |

### 4. 协议长什么样（HTTP + JSON，人人看得懂）

`POST /order`（点单端 → 接单端）

```json
{
  "type": "order",
  "id": "o1792516487925",
  "from": "order",
  "fromName": "小窝·点单",
  "ts": 1792516487925,
  "note": "今天也要开心呀",
  "total": 13,
  "replyHost": "192.168.1.7",
  "replyPort": 41651,
  "items": [
    {"name":"要亲亲","qty":1,"price":5,"cat":0,"emoji":"💋"},
    {"name":"要抱抱","qty":2,"price":4,"cat":1,"emoji":"🫂"}
  ]
}
```

接单端立刻回：

```json
{"ok": true, "type": "ack", "role": "recv", "msg": "收到啦，马上配送", "ts": 1792516488000}
```

`POST /heart`（接单端 → 点单端，"回个爱心"）：

```json
{"type":"heart","fromName":"小窝·接单","text":"抱抱你，已接单！","ts":1792516490410}
```

要点：**订单里带了 `replyHost` / `replyPort`**，所以接单端知道该往哪儿回爱心，
不需要反向再做一次发现。这一点是双向通信的关键。

### 5. 代码在哪

| 文件 | 作用 |
|---|---|
| `core/Net.java` | 全部网络能力：迷你 HTTP 服务器、HTTP 客户端、UDP 广播发现、本机 IP |
| `core/Protocol.java` | 消息格式（订单/确认/爱心/发现的 JSON 编解码） |
| `core/Json.java` | 手写的极简 JSON，不引第三方库 |
| `core/Menu.java` | 菜单数据：想你了 / 点外卖 / 做什么 / 有情绪 / 不够花 |
| `ui/OrderScreen.java` | 点单端界面 |
| `ui/RecvScreen.java` | 接单端界面 |
| `ui/HeartBurstView.java` | 全屏爱心粒子特效（Canvas + Choreographer 逐帧） |
| `ui/CatFaceView.java` | 用 Canvas 画的小猫头像，全矢量、零图片 |

---

## 一·二、v1.2 的界面：五个 Tab

底部导航现在是真的能切页了，两个 App 都是同一套结构：

| Tab | 点单端 | 接单端 |
|---|---|---|
| 🏠 厨房 | 分类侧栏 + 菜品列表 + 购物车 | 接单状态卡 + 最近一单 |
| 🧾 订单 | 下过的单，可「再来一单」 | 收到的全部订单，可「回个爱心」 |
| 🧊 冰箱 | 囤着的菜，长按菜品即可存入；可"把冰箱里的都点一份" | TA 常点排行榜（自动统计） |
| 📖 管理 | 菜单一览（每类几道、整类总价） | 端口 / 引擎 / 穿透状态、开关接单 |
| 😊 我 | 连接设置、清空记录、关于 | 累计份数、最常点的菜、关于 |

数据都存在本机 `SharedPreferences`（JSON），关掉 App 再打开还在。

### 货币单位改成了 ❤

点单端所有价格从 `￥` 换成了 `❤`：购物车「合计 ❤10」、菜品「❤5」、订单卡「合计 ❤13」。
接单端的汇总同样用 ❤。

### 猫猫形象换成了「呆猫 / 猫八条」

原来那个小猫是代码画的，现在换成了网上流传的「呆猫（猫八条）」表情包：
灰猫、黄圈大眼，和你给的截图里是同一只。

![选用的 8 张](./docs/cats.png)

- 存放位置：`assets/cats/0.png` ~ `7.png`（288×288，构建时打进 APK 的 assets）
- 加载代码：`ui/CatImage.java`，`ImageView` + `clipToOutline` 做圆角
- 菜单里每道菜的 `cat` 字段就是 0~7 的图号；换图只要替换 assets 里的文件

> ⚠️ **版权说明**：这些图是网上流传的他人表情包作品（原出处见 SakuraFrp 之外的水印/作者），
> 不是免费素材。我只把它们放在你俩自用的这个 App 里，**不要公开发布、不要上架应用商店**。
> 如果以后想发布，建议换成自己画/买授权的图，代码里改 `assets/cats/` 就行，不用动逻辑。

---

## 一·五、SakuraFrp 一键联机（异地也能点单）

接单端界面里有个紫色按钮 **「🚀 一键穿透（SakuraFrp）」**，
它把「开隧道」这件事做进了 App 里，不用再装 SakuraFrp 启动器、不用敲命令。

### 用起来是什么样

1. 打开接单端 → 点「🚀 一键穿透」
2. 粘贴 **访问密钥** → 点「登录」
3. 选一条隧道 → 点「开始穿透」
4. App 会显示一个公网地址（形如 `cn-bgp-1.natfrp.cloud:35821`），点「复制地址发给 TA」
5. 对方在点单端点「⇄ 连接」，把地址粘进去 —— 完事

### 为什么是「访问密钥」而不是账号密码？

SakuraFrp 的开放 API v4 **只认访问密钥**（HTTP Header `Authorization: Bearer <密钥>`），
它的接口里根本没有「邮箱+密码登录」这一项。访问密钥在
[natfrp.com/user](https://www.natfrp.com/user) → 用户信息 → 「查看访问密钥」。
好处是：你不需要把登录密码交给这个 App，泄露了也能随时重置。

### 它是怎么做到的

```
App ──HTTPS──▶ api.natfrp.com/v4/user/info   校验密钥、拿昵称
                /v4/tunnels                   列出隧道（id、本地端口、远程端口）
                /v4/nodes                     拿节点域名，拼出公网地址
App ──本地──▶  exec 内置 frpc:  libfrpc.so --disable_log_color -n -f <密钥>:<隧道ID>
                                          │
                                          └─ frpc 自己连 SakuraFrp 服务端，把本机 8765 暴露出去
```

几个关键工程点：

| 问题 | 做法 |
|---|---|
| 怎么把 frpc 塞进 APK | 取官方 Android 启动器里的 `lib/arm64-v8a/libfrpc.so`（0.51.0-sakura-14），构建时 `aapt add` 打进 `lib/arm64-v8a/` |
| 为什么必须放 lib/ | Android 10 起禁止 App 执行自己数据目录里的文件（W^X），只有 `nativeLibraryDir` 可执行 → 清单里要 `android:extractNativeLibs="true"` |
| 隧道本地端口和接单端口不一致怎么办 | 面板上会直接红字警告，并给一个「把接单端口改成 X」的按钮，App 会重新绑定监听端口 |
| 公网地址从哪来 | 优先从 frpc 日志里正则抓（`*.natfrp.cloud:端口`），抓不到就用 API 的「节点域名 + 远程端口」兜底 |

### 第一次用之前，先建一条隧道

到 [natfrp.com/tunnel](https://www.natfrp.com/tunnel) 新建 **TCP** 隧道：

- 本地地址：`127.0.0.1`
- 本地端口：`8765`（就是接单端的默认端口）
- 节点：随便选一个没满的

建好后回到 App 里「登录 → 选隧道 → 开始穿透」即可。

> 注意：SakuraFrp 对**公开的游戏服务器**有节点限制。我们这个只是两个人之间点单用，
> 不公开、不宣传，用普通节点就行。

### 边界与后续

- frpc 是 App 的子进程，**App 进程被系统回收时隧道会断**。
  想要「退出界面也一直在线」，下一步该做的是把接单服务和 frpc 搬进**前台服务**（Foreground Service）并挂常驻通知。
- 同一时刻一条隧道只能被一个 frpc 实例使用；如果提示「隧道已在别处运行」，把别处的停掉。
- 访问密钥存在 App 私有 SharedPreferences 里（已关闭 `allowBackup`，不会被上传到云备份）。

---

## 一·六、v1.6 增量：崩溃修复 + 自动更新 + 通知/悬浮窗

### 修复了一批真机问题
- **点连接崩溃**：旧代码把对话框里的 `TextView` 强转成 `EditText` → `ClassCastException`。已修，并恢复「自动发现」按钮。
- **两个 App 装不上的「与现有软件冲突」**：根因是自动更新的 `ContentProvider` 把 `authorities` 写死了同一个值 `com.couple.xiaowo.updates`。Android 要求 authority 全局唯一，两个 App 撞了就冲突（跟签名无关、卸载也没用）。已改成各自包名：
  - 点单端 `com.couple.xiaowo.order.updates`
  - 接单端 `com.couple.xiaowo.recv.updates`
  - build.sh 里用 `@PKG@` 占位符替换。
- 接单端悬浮窗不消失：新消息先撤旧气泡 + 定时改用主线程 Handler（原来 `View.postDelayed` 未附着时不触发）。
- 点单端悬浮窗偏小：改成满宽卡片。
- 版本号文字写死 v1.3：改成实时读 `PackageInfo.versionName`。
- 添加菜谱做成真表单（菜名/心动值/emoji/说明/猫猫封面）。

### 通知 / 悬浮窗 + 可调提醒程度
「我」页面有「🔔 提醒程度」三档：完全安静 / 只发通知 / 悬浮窗蹦出来，两边各自独立。
点消息/订单/公告时按档位走 `AlertCenter`：系统通知（Android 8+ Channel + 权限申请）或系统悬浮窗（`SYSTEM_ALERT_WINDOW` + 爱心粒子）。悬浮窗不拦截触摸，4 秒自动消失。

### 自动更新（App 内自更新 + 独立发布工具链）
见 [docs/auto-update.md](./docs/auto-update.md)。

一句话原理：App 拉 version.json → 比对 versionCode → 下载 APK → 调系统安装器。

用法（发布侧）：
```bash
BASE_URL=https://你的地址/xiaowo bash tools/publish.sh local
# 生成 dist/（version.json + 两个 APK），把三个文件传到同目录
```
App 侧：我 → 自动更新 → 设置更新地址 填 `https://你的地址/xiaowo/version.json` → 检查更新。

> ⚠️ 重要：每次发新版，**两端 versionCode 必须一起往上加**（build.sh 统一管，不用手改）。
> 且 `raw.githubusercontent.com` 在国内基本不可用、GitHub Release 资产会跳转到不稳定的 CDN，
> 国内最稳的是对象存储（Cloudflare R2 / 阿里云 OSS）或自有服务器直链。详见文档。

---

## 二、编译

不需要 Android Studio，不需要 Gradle。用 Ubuntu 自带的 arm64 `aapt` + `zipalign`，
加上 SDK 里的纯 Java 工具 `d8.jar` / `apksigner.jar`：

```bash
cd couple-order
bash build.sh
# 产物：
#   build/xiaowo-order.apk
#   build/xiaowo-recv.apk
```

依赖：

```bash
apt-get install -y openjdk-17-jdk-headless aapt zipalign
```

`tools/sdk/android.jar`（编译用）和 `tools/sdk/android-14/lib/{d8,apksigner}.jar`
已随工程放好；`tools/prebuilt/frpc_arm64` 是内置的官方 frpc，构建「接单端」时会打进 APK。

> 想用 Android Studio：直接把 `src/` 当一个普通 Java 模块导入，
> `res/` + `AndroidManifest.xml.in` 换成标准 manifest 即可，代码不用改。

---

## 三、跑起来

### 方式 A：两台手机（真实场景）

1. 两台手机连**同一个 Wi-Fi**；
2. 各自装上对应的 APK；
3. 先打开 `小窝·接单`，它会显示 `192.168.x.x:8765`（点一下可复制）；
4. 再打开 `小窝·点单`，点右上「⇄ 连接」→「自动发现」，或直接把地址填进去；
5. 点菜 → 右下「下单」→ 自己这边爱心炸开 → 对方手机同时爱心炸开并震动。

### 方式 B：同一台手机自测（现在就验证）

两个都装上，先开接单端，再开点单端，直接点「下单」——
点单端会走 `127.0.0.1` 兜底把订单送到接单端。

### 方式 C：跨网络

见上面「如果两台手机不在同一个网络」。

---

## 四、常见问题

**Q：一直"没连上接单端"？**
- 接单端没点「开始接单」（状态点变绿才算在收听）；
- 两个 App 不在同一 Wi-Fi，或连的是"访客网络"（AP 隔离，禁止设备互访）；
- 部分路由器/校园网禁止 UDP 广播 → 改用「连接」里手动填 IP；
- 接了 VPN（部分 VPN 会切断局域网）→ 用 Tailscale 的虚拟 IP。

**Q：爱心特效没出来？**
`HeartBurstView` 在 `MainActivity` 里作为最上层覆盖，`burst()` 由 `HeartHost` 回调触发。
如果 Activity 被系统回收后重新创建，特效实例会重建，不影响。

**Q：想改菜单/价格？**
`core/Menu.java` 里改 `add(...)`，四个参数依次是 名称、价格（心动值）、猫猫样式 0~7、emoji、一句话文案。

**Q：想改配色？**
`ui/Ui.java` 顶部那几个常量（`PURPLE` / `HEART` / `BG` …）。

**Q：想加"添加菜谱"真功能？**
点单端 `OrderScreen` 里「＋ 添加菜谱」现在只弹提示，接上 `SharedPreferences` 存自定义菜谱即可。

---

## 五、空间回收

工具链只是编译期用，交付后可以删掉约 **550 MB**：

```bash
rm -rf /sdcard/dshwksp/couple-order/tools/sdk
apt-get purge -y openjdk-17-jdk-headless     # 262 MB，以后要编译再装回来
apt-get clean
```

源码 + 两个 APK 加起来不到 1 MB。
