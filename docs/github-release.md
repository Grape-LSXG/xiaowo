# 上传到 GitHub + 用 GitHub 做自动更新

## 先说结论

**GitHub 这条路能让点单端真正自动更新**，而且比"从接单端拿"可靠得多：

- 不需要连上接单端，**只要手机能上网就行**
- 异地也能更新（中转传不动 6MB 的包，GitHub 能）
- 有版本记录，出问题能回退

关键实测数据（这台机器上）：

| 域名 | 状态 | 用不用 |
|---|---|---|
| `raw.githubusercontent.com` | **不通（HTTP 000）** | ❌ 不用 |
| `api.github.com` | 通（200，约 0.5s） | ✅ 查版本 |
| `github.com` | 通（200） | ✅ |
| `release-assets.githubusercontent.com` | 通（206 分段下载成功） | ✅ 下 APK |

所以实现上**刻意绕开 raw 直链**，全部走 API。

---

## 我做了什么（代码已就绪）

### 1. `core/GithubRelease.java`

- `check(recv?)` → `GET /repos/<repo>/releases?per_page=20`
- 按 tag 前缀区分包：`order-v<版本号>` / `recv-v<版本号>`
- 版本号直接取 tag 里的数字（`order-v21` → 21），不用额外维护 version.json
- `download()` → 用 release 的 **asset API url** + `Accept: application/octet-stream`
  （会 302 到 `release-assets.githubusercontent.com`，实测可下）

### 2. `ui/GitHubCard.java`

「我」页新增一张卡：

```
🚀 从 GitHub 更新          v3.0
点「检查更新」看看有没有新版本。
不需要连上对方 —— 只要这台手机能上网就能更新。
[检查更新]
仓库：Grape-LSXG/xiaowo        ← 可点改
☑ 打开 App 时自动检查
```

- 手动点 → 有新版弹确认框 → 下载 → 调系统安装器
- 自动检查 → **静默**，只在卡片上留文字，不弹框打扰

### 3. 启动自动检查

`OrderScreen.scheduleAutoPeerUpdate()` 里，先问 GitHub（1.2 秒后，
等首屏渲染完），再走原来的"从接单端直连拿"。

两条路都保留，因为：
- 同一 Wi-Fi 下从接单端拿更快、不走外网
- 异地只有 GitHub 能更新

### 4. 点单端自动从接单端更新（也修了）

原来 `scheduleAutoPeerUpdate` 里如果局域网发现不到人，直接 `return`
—— 所以异地时"自动更新"永远不触发。

现在改成：发现不到人 + 已配对中转 → 走中转问版本。

---

## 你需要做的（三步）

### 第 1 步：在 GitHub 建仓库

浏览器打开 <https://github.com/new>

- Repository name: **`xiaowo`**
- 选 **Public**（公开仓库才能免 token 读取 release）
- **不要**勾 Add README（避免生成多余提交）
- 点 Create repository

> 为什么必须你来建：我拿到的 GitHub token 只有读权限，
> 创建仓库返回 `403 Resource not accessible by personal access token`。
> 重试没有用，权限是 token 决定的。

### 第 2 步：发两个 Release

仓库建好后，打开 <https://github.com/Grape-LSXG/xiaowo/releases/new>

**第一个 release（点单端）：**

| 字段 | 填什么 |
|---|---|
| Choose a tag | 输入 `order-v21` → 点 **Create new tag** |
| Release title | `点单端 v3.0` |
| Attach binaries | 拖入 `xiaowo-order-v21.apk` |
| 描述 | 随便写，会显示在 App 的更新提示里 |

点 **Publish release**。

**第二个 release（接单端）：**

| 字段 | 填什么 |
|---|---|
| tag | `recv-v21` |
| title | `接单端 v3.0` |
| 附件 | `xiaowo-recv-v21.apk` |

两个 APK 已经放在手机的 `Download/xiaowo-release/` 里了。

> **tag 命名必须严格照这个格式**：`order-v21` / `recv-v21`。
> 代码靠前缀判断这是哪个包，靠 `v` 后面的数字判断版本新旧。

### 第 3 步：装上新版本试一下

装好 v3.0 后，打开「我」页 → 滑到「🚀 从 GitHub 更新」→ 点「检查更新」。

- 如果显示 **"已经是最新版"** → 说明链路通了（因为你刚发的就是 21 版）
- 如果显示 **"连不上 GitHub"** → 把具体报错发我
- 如果显示 **"发现新版"** → 说明还能识别到更高的版本，也正常

想验证"真的能更新"，就把 versionCode 提到 22 再发一次 release，
App 上点检查应该会提示有新版本并下载。

---

## 备选：如果你不想用 GitHub

那还有一条**这台机器上已验证可用的方案**——把 APK 放到任意能直链下载的地方
（对象存储、你自己的服务器），然后在 App 里填 version.json 地址。

原来的 `Updater` 就是做这件事的，`docs/auto-update.md` 里有说明。
但现在 GitHub 这条路更省事，因为不用自己维护服务器。

---

## 关于"有没有其他连接方法"

除了已经实现的三种（同 Wi-Fi / Tailscale / MQTT 中转），还有两条：

| 方式 | 说明 | 我的建议 |
|---|---|---|
| **GitHub Release 当更新通道** | 刚做的。只解决"更新"，不解决"实时收单" | ✅ 已实现 |
| **打洞 + 中继兜底** | 真 P2P，需要 STUN 打洞，失败时自己搭中继转发 | 工作量 2–3 人天，但比 MQTT 快得多 |

MQTT 中转的短板是**延迟**（要走公共 broker，通常 100–500ms）
和**QoS0 不保证送达**。如果你们以后觉得"点了单对方半天没反应"，
下一步就该做打洞。目前先用着，够用。


---

# 附：能不能把"消息"和"更新"都走 GitHub？

**不能。更新可以，消息不行。** 三个硬性障碍，都是实测出来的：

## 障碍 1：匿名配额只有 60 次/小时

```
core: limit=60 remaining=60
search: limit=10
```

一台手机开 App 聊一小时（按每分钟 6 条算）就是 **360 次请求**，
两台手机 720 次。**不到 10 分钟就打光配额**，之后全部 403。

对比 MQTT：**一条长连接 + 心跳**，一小时几乎不产生额外请求。
这不是"够不够快"的问题，是**架构根本不同**——GitHub 是请求-响应，
MQTT 是长连接推送。

## 障碍 2：GitHub 不给你往"对方的仓库"写

实测（未认证）：

```
POST /repos/.../issues → 401
```

就算是你们自己的仓库，`POST /repos/<me>/<repo>/issues` 也需要 token，
而 token **不能放进 APK**（反编译就泄露，别人能用它操作你的整个账号）。

所以消息只能**往一个你控制的仓库里"堆"**，这就变成：
- 每条消息 = 一次 issue 或一次 commit
- 读消息 = 轮询 issue 列表
- 这不是聊天，是**用 Git 当数据库**

## 障碍 3：延迟 0.4–0.7 秒，且只能轮询

实测读一次 releases：

```
releases 读取: 0.657s / 0.370s / 0.367s
```

这还是**一次请求**的耗时。要做到"发消息对方马上收到"，就得不停地轮询，
而轮询又会撞上障碍 1 的配额。

MQTT 中转是**推送**：连上之后对方发过来会立即到达，不产生额外请求。

---

## 结论：各自的定位

| 用途 | 走哪条 | 为什么 |
|---|---|---|
| **实时消息**（订单、聊天、爱心） | MQTT 中转 | 长连接推送、零额外请求、秒级到达 |
| **版本更新**（6MB APK） | GitHub Release | 带宽不心疼、有版本记录、不依赖对方在线 |

**两者不是竞争关系，是互补的：**

- MQTT 不能传 6MB（公共 broker 不适合，而且 QoS0 会丢）
- GitHub 不能做实时（配额 + 延迟 + 只能轮询）

所以现在的架构是对的，不该合并。

---

## 如果哪天想再优化

真正值得做的不是"合并到 GitHub"，而是**换掉 MQTT 的短板**：

### 方案 A：打洞（真 P2P）

- 用 STUN 拿到各自的公网映射，直接连
- 延迟降到几十毫秒，也不依赖公共 broker
- 打洞失败时用中继兜底
- 工作量 **2–3 人天**

### 方案 B：自建轻量 MQTT broker

- 一台小服务器（甚至树莓派）跑 mosquitto
- 解决公共 broker 的隐私问题（消息不再经过第三方）
- 成本：一台常开的机器 + 一点电费
- 工作量 **1 人天**

**我的建议**：先用着现在的方案。如果实际用下来觉得"点了单对方半天没反应"，
再考虑方案 A。
