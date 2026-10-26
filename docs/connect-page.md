# 连接方式整页（v2.7 / v2.9）

## 为什么要做这个页面

之前三种连接方式散落在不同地方（顶部按钮、状态卡、对话框），
用户不知道每种是干什么的、什么时候该用哪个。更糟的是**状态是假的**。

## 页面结构

```
┌─ 连接方式 ────────────────────┐
│ 让猫猫找到你 / 找到张老师        │  ← 按角色自动换文案
│ 三种方式任意一种通了就能用        │
├──────────────────────────────┤
│ 🏠 同一个 Wi-Fi                │
│    最快，完全自动               │
│    [🔍 在局域网里找对方]        │
├──────────────────────────────┤
│ 🔗 手动填地址 / 🛰 Tailscale    │
│    [填地址] [测试连通]          │
├──────────────────────────────┤
│ 🌐 异地中转                    │
│    配对一次永久有效             │
│    [生成房间码] [复制] [解除]    │
│    中转状态：在线（broker…）     │
├──────────────────────────────┤
│ 怎么选？（三种场景的建议）        │
└──────────────────────────────┘
```

**同一套代码两个 App 共用**（`ui/ConnectPage.java`），
靠 `isRecv` 参数切换文案和操作：
- 接单端："让猫猫找到你" / "生成房间码"
- 点单端："找到张老师" / "填房间码加入"

## 三种通道各自的用法（页面上原样写着）

| 通道 | 用法 |
|---|---|
| 同一个 Wi-Fi | 什么都不用填，点按钮在局域网广播找对方 |
| Tailscale / 手动 | 两台手机同账号登 Tailscale，把对方状态卡的 100.x 填进来；或直接填 192.168.x.x:8765 |
| 异地中转 | 接单端生成 6 位房间码 → 发给对方 → 对方填一次 → 永久有效 |

---

## 修复一：加入房间后仍显示"未连接"

### 原因

`OrderScreen.peerText()` 只看直连地址：

```java
if (peerHost != null && peerPort > 0) {
    return "⇄ 已连接 " + peerHost + ":" + peerPort;
}
return "⇄ 未连接（会自动发现，也可以手动填 IP）";
```

加入房间只写 `relay_room`，**不写 `peer_host`**，所以永远走 else 分支。

而且这个"已连接"本身就是假的 —— 只要存了地址就显示，哪怕对方根本没开机。

### 修法

按**实际可用的通道**说话：

```java
有直连地址 → "⇄ 直连 1.2.3.4:8765"
只有房间码 → "🌐 中转已配对 · 房间 SL5LWV"
都没有     → "⇄ 未连接（点这里配一下）"
```

真机验证：

```
改前：⇄ 未连接（会自动发现，也可以手动填 IP）
改后：🌐 中转已配对 · 房间 SL5LWV
```

### 顺带修的：状态收归 Activity

之前 `RecvScreen` 和 `OrderScreen` **各自**维护一个 `Mqtt.Sub`。
换页面、切 Tab 就可能被停掉或状态不同步。

现在只有一份，放在 `MainActivity`：
- `startRelay()` / `restartRelay()` 统一管理
- 任何页面要显示状态就调 `bindRelayStatus(TextView)`
- 收到的消息由 `deliverRelay()` 派发给对应界面

---

## 修复二：点单端拿不到更新

### 原因

`ApkRelay` **只走直连 HTTP**：

```java
URL url = Net.buildUrl(host, port, PATH_INFO);   // 直连
```

异地时连不上对方 → `check()` 直接报错返回 → **永远走不到中转那条路**。

而 MQTT 中转能传消息，但传不了 6MB 的 APK —— 所以下载这一步本来就只能靠直连。

### 修法

把"查版本"和"下载包"拆开：

- **查版本**：直连失败时改走中转（消息很小，能传）
- **下载包**：只有直连能下，中转明确告知"传不动，请在同一 Wi-Fi 下更新"

`PeerUpdateCard.runCheck()` 里加了这段兜底：直连失败 + 已配对 → `askViaRelay()`。

点单端收到版本信息后：

```
张老师那边有新版 v2.9（你这版是 v2.8）
┌ 有新版本 ─────────────────┐
│ 安装包有 6MB，中转通道传不动。│
│ 请在同一个 Wi-Fi 下点「检查  │
│ 并更新」，或让张老师直接把    │
│ APK 发给你。               │
└──────────────────────────┘
```

### 顺带修的真 bug：消息回环

测试时发现点单端收到的"回包"其实是**自己发出的查询**。

原因：MQTT 订阅会把自己 PUBLISH 的消息也投递回来。
`relayFrom` 过滤没在这一层生效，于是点单端把自己的查询当成对方回包，
解析出 `versionCode=0`。

修法：`deliverRelay()` 里 `w.from.equals(myRole)` 直接丢弃。

实测：

```
跳过自己发的，符合 App 过滤逻辑
点单端收到 payload = {"ok":true,"versionCode":17,"versionName":"2.6","size":742685}
PASS 中转能查到版本
```

---

## 修复三：页面内容被裁掉（v2.9）

### 现象

页面 dump 出来的坐标是 `区域=191,2628,1068,2106` ——
`bottom (2106) < top (2628)`，高度是负数，等于完全不可见。

### 原因

我在 `ConnectPage` 里又套了一层 `ScrollView`，
而 `DialogKit.custom()` 内部**已经有一个** `MaxHeightScroll`（屏幕高度 50%）。
**嵌套滚动**让内层的高度计算崩掉。

```java
// 错的
ScrollView scroll = new ScrollView(act);
scroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, dp(420)));
DialogKit.custom(act, "连接方式", scroll, ...);

// 对的：直接交给 DialogKit，它自己会滚
DialogKit.custom(act, "连接方式", root, ...);
```

### 教训

`DialogKit.custom` 的第二个参数是**内容本身**，不是滚动容器。
所有需要滚动的对话框都应该直接把内容交进去。

---

## 数据流（最终形态）

```
                    ┌─────────────────┐
                    │  MainActivity   │
                    │  relaySub (唯一) │
                    └────────┬────────┘
                             │ deliverRelay(type, payload)
              ┌──────────────┴──────────────┐
              ▼                             ▼
      OrderScreen                    RecvScreen
      onRelayMessage()               onRelayMessage()
      聊天/公告/爱心/版本               订单/聊天/公告/爱心/回版本

senders:  局域网直连 → Tailscale → 中转（按顺序尝试）
```

## 实测记录

| 项 | 结果 |
|---|---|
| 加入房间后状态文案 | `🌐 中转已配对 · 房间 SL5LWV` ✅ |
| 中转在线状态 | `在线（broker.emqx.io）` ✅ |
| 中转查版本 | `versionCode=17 versionName=2.6` ✅ |
| 协议回归测试 | 17/17 ✅ |

---

## 7. v4.10 起：房间码和服务器分成两种方式

以前的界面只有「异地中转」一栏，写着"房间码"，可真正连的是 APK 里预置的 EMQX 服务器 ——
用户填了房间码以为在用它，实际根本没参与。现在 `Relay.KEY_MODE` 只有一个值决定一切，
界面选哪种就只显示哪种：

| 模式 | 数据 | 界面 |
| --- | --- | --- |
| `cloud` ☁️ 小窝云 | `assets/cloud.properties` 的 host/user/pass/8883 + 预置房间码 | 不显示房间码，只说"已启用" |
| `public` 📻 房间码 | 公共 broker（按房间哈希轮换）+ 用户自己编的 6 位码 | 显示房间码 + 复制/生成 |
| `self` 🖥 自建服务器 | 用户填的 host/port/账号/TLS + 房间码 | 显示地址 + 房间码 |

- 实现：[`Relay.mode()/setMode()`](../src/com/couple/xiaowo/core/Relay.java)、
  [`Mqtt.configure()`](../src/com/couple/xiaowo/core/Mqtt.java)（按模式装载 host/账号/TLS）、
  [`ConnectPage.relayCard()`](../src/com/couple/xiaowo/ui/ConnectPage.java)（三选一 + 该模式的操作按钮）、
  [`MainActivity.relayStatusText()`](../src/com/couple/xiaowo/ui/MainActivity.java)（首页那行按模式说话）。
- 手动填房间码 / 保存自建服务器会自动切到对应模式，避免"界面上是房间码、底层却是云"。
- 首页状态行只读订阅真实状态（连上才写"在线"），不再是"存过房间码就算配对"。

