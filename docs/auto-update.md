# 自动更新：把 APK 传上去，App 自己就能升级

> 不用服务器，不用后端，不用数据库。把两个 APK 和一个 `version.json` 传到一个**能直链下载**的地方，
> 然后在 App 里填 `version.json` 的地址，以后每次点「检查更新」就能拿到新版本。

配套工具（在工程根目录跑）：

| 命令 | 干什么 |
| --- | --- |
| `bash tools/publish.sh local` | 打包 + 生成 `dist/`，然后告诉你下一步（适合自己传网盘/服务器） |
| `bash tools/publish.sh github` | 打包 + 生成 `dist/`，然后打印 git 推送命令（适合 GitHub Pages / Release） |
| `python3 tools/make_version_json.py --verify dist` | 只校验 `dist/version.json`，不重新打包 |

---

## 1. 原理一句话

> App 启动时拉取 `version.json`，拿里面的 `versionCode` 跟自己安装包的版本比，
> 发现对方更大就下载 `url` 指向的 APK，最后调起系统安装器让用户点「安装」。

代码在 [`src/com/couple/xiaowo/core/Updater.java`](../src/com/couple/xiaowo/core/Updater.java)：
`Updater.check()` 拉清单并比较版本，`Updater.download()` 下到 `cacheDir`，`Updater.install()`
通过 `FileProvider`（`content://`）交给系统安装器。**整个过程没有任何后端参与**，`version.json` 就是一个静态文件。

> ⚠️ Android **不允许** App 静默装 APK（除非是系统应用/设备管理员），所以最后一步一定是
> 系统安装界面，用户得点一下「安装」。这是平台限制，绕不过去，不要试图绕过。

---

## 2. 快速上手（三步）

```bash
cd /sdcard/dshwksp/couple-order

# ① 打包 + 生成 dist/（version.json 里的版本号是从 APK 里读的，不用手改）
BASE_URL=https://你的地址/xiaowo bash tools/publish.sh local

# ② 把 dist/ 里这 3 个文件传到那个地址下（同目录、文件名不要改）
#    version.json
#    xiaowo-order.apk
#    xiaowo-recv.apk

# ③ App 里：「我」→ 自动更新 → ⚙ 设置更新地址 → 填 https://你的地址/xiaowo/version.json
```

之后升级流程就是：改 `AndroidManifest.xml.in` 里的 `versionCode` / `versionName` → 重跑上面 ① ② →
两台手机上点「检查更新」。

> 现在 `AndroidManifest.xml.in` 里是 `versionCode="7"` / `versionName="1.6"`，
> 下次发版改成 `8` / `1.7` 就行。**`versionCode` 必须严格递增**，它才是判断新旧的依据。

---

## 3. `dist/` 长什么样

```
dist/
├── version.json          ← App 要拉的就是这个
├── xiaowo-order.apk      ← 点单端
└── xiaowo-recv.apk       ← 接单端
```

`version.json` 的真实内容（下面是本工具实际跑出来的输出，只把 `url` 里的地址换成了占位符）：

```json
{
  "order": {
    "versionCode": 7,
    "versionName": "1.6",
    "url": "https://你的地址/xiaowo/xiaowo-order.apk",
    "notes": "问题修复与体验优化"
  },
  "recv": {
    "versionCode": 7,
    "versionName": "1.6",
    "url": "https://你的地址/xiaowo/xiaowo-recv.apk",
    "notes": "问题修复与体验优化"
  }
}
```

---

## 4. `version.json` 字段说明

顶层两个 key：**`order`**（点单端）和 **`recv`**（接单端）。
App 会按自己编译时写死的 `BuildInfo.ROLE` 去取对应的那一段 —— 字段名**不能改**，改了 App 就读不到
（`Updater` 用 `Json.num/str` 按名字硬取）。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `versionCode` | 整数 | ✅ | 新包的版本号。**必须大于手机上装的那个**才会提示更新。对应清单里的 `android:versionCode` |
| `versionName` | 字符串 | ✅ | 显示给用户看的版本，如 `"1.6"`。只用于展示，不参与比较 |
| `url` | 字符串 | ✅ | APK 的 **HTTPS 直链**，必须点开就直接是 APK 文件流 |
| `notes` | 字符串 | ⬜ | 更新说明，会显示在「发现新版本 1.6：这里的内容，正在下载…」。可以省略 |

生成脚本会额外做这些检查（不满足直接报错退出）：

- `versionCode` 必须是正整数，`versionName` 非空；
- `url` 必须 `http(s)://` 开头且以 `.apk` 结尾（防止填成网盘页面）；
- 不允许出现 `versionCode` / `versionName` / `url` / `notes` 之外的字段；
- 两端 `versionCode` 不一致会**警告**（一端更新了另一端没更新，很常见）。

`aapt dump badging` 是唯一读取版本号的来源：

```bash
aapt dump badging build/xiaowo-order.apk | head -1
# package: name='com.couple.xiaowo.order' versionCode='7' versionName='1.6' ...
```

所以**先改清单 → 再打包 → 再生成清单**，顺序别反。

---

## 5. 三种免费托管方案

### 方案 A：GitHub Releases（适合长期发版，有版本记录）

每个版本一个 Tag，APK 当 Release 资产传上去，**资产直链不会因为改代码而失效**（改代码影响的是 raw 链接，不是 Release 资产）。

> ⚠️ 前提：你的网络能访问 `github.com` **和** `release-assets.githubusercontent.com`。
> Release 直链会 302 跳到后者真正传输文件，这个域名在国内是**时通时不通**的（见「常见坑 ①」）。
> 如果两台手机所在网络不稳，直接用方案 C。

**步骤：**

1. 建仓库，把工程推上去（`git init -b main` → `git remote add origin ...` → `git push`）。
2. 网页上打开仓库 → **Releases** → **Draft a new release**。
3. **Tag** 填 `v7`（跟 `versionCode` 对齐，方便自己看）。
4. 把 `dist/xiaowo-order.apk` 和 `dist/xiaowo-recv.apk` 两个文件拖进 assets 区，发布。
5. 资产的直链长这样（把 `USER`/`REPO` 换成你自己的）：

   ```
   https://github.com/USER/REPO/releases/download/v7/xiaowo-order.apk
   https://github.com/USER/REPO/releases/download/v7/xiaowo-recv.apk
   ```

6. Releases 的路径不规则（带 tag），所以生成清单时**直接给完整直链**，不要用 `--base-url` 拼：

   ```bash
   python3 tools/make_version_json.py --no-copy \
       --order-url https://github.com/USER/REPO/releases/download/v7/xiaowo-order.apk \
       --recv-url  https://github.com/USER/REPO/releases/download/v7/xiaowo-recv.apk \
       --out dist
   ```

   > `--no-copy` 表示不把 APK 再复制一份到 `dist/` —— APK 已经作为 Release 资产传上去了。
   > 此时 `--verify` 会提示 `dist/` 里没有 APK，这是正常的，可以忽略。

7. 最后把 `dist/version.json` 放到一个**稳定的直链**上（因为它不含 tag，每个版本都覆盖同一个文件）。
   推荐放在方案 B 的 Pages 目录里：

   ```
   https://USER.github.io/REPO/xiaowo/version.json
   ```

**优点**：APK 直链稳定、有下载计数、能回滚旧版本。**缺点**：`version.json` 还得另外找地方放；
依赖 `release-assets.githubusercontent.com` 的连通性。

---

### 方案 B：GitHub Pages（最简单，纯静态直链）

整个 `dist/` 目录当静态站点发布，一个地址搞定，`version.json` 和 APK 同目录 —— 最省心。

**步骤：**

1. 仓库推上去，仓库 **Settings → Pages** → Source 选 **Deploy from a branch** → 分支 `main`、目录 `/ (root)`，保存。
2. 把产物拷进仓库的子目录并提交：

   ```bash
   cd /sdcard/dshwksp/couple-order
   BASE_URL=https://USER.github.io/REPO/xiaowo bash tools/publish.sh github
   # 按脚本打印的命令执行：
   mkdir -p xiaowo && cp dist/* xiaowo/
   git add xiaowo && git commit -m "release 1.6"
   git push origin main
   ```

3. 等 1 分钟左右 Pages 构建完，地址就是：

   ```
   https://USER.github.io/REPO/xiaowo/version.json
   https://USER.github.io/REPO/xiaowo/xiaowo-order.apk
   https://USER.github.io/REPO/xiaowo/xiaowo-recv.apk
   ```

4. 把 `version.json` 那个地址填进 App。

**优点**：一次搞定，路径规则，`--base-url` 直接拼。**缺点**：每次发版仓库体积都涨（旧的 APK 留在 git 历史里），
APK 多了以后 clone 会变慢 —— 发几十个版本后建议换方案 A。

> `publish.sh github` 会尝试从 `git remote origin` 猜出 `https://USER.github.io/REPO/xiaowo`。
> 猜不出来（没配 remote / 不是 GitHub）时会直接报错要你显式给 `BASE_URL`，不会瞎编。

---

### 方案 C：对象存储 / 自己的服务器

**Cloudflare R2**、**阿里云 OSS**、**腾讯云 COS**、**七牛**，或者你自己有台 VPS 都行。
适合已经有机器的，国内访问通常比 GitHub 稳。

**步骤（以任意 S3 兼容对象存储为例）：**

1. 建一个 Bucket，用来放小窝的更新文件。
2. **关键：把这个 Bucket / 目录设成公共读**。App 是匿名 GET，不做任何鉴权，私有 Bucket 会直接 403。
3. 上传 `dist/` 里那 3 个文件到同一个目录（例如 `xiaowo/`）。
4. **关键：`Content-Type` 要对**，否则浏览器会下载成 `.txt` 或者直接显示文本，App 也可能解析异常：

   | 文件 | Content-Type |
   | --- | --- |
   | `version.json` | `application/json` |
   | `*.apk` | `application/vnd.android.package-archive` |

   上传后一定要用 `curl -I` 确认一下：

   ```bash
   curl -I https://你的域名/xiaowo/version.json    # 期望 200 + application/json
   curl -I https://你的域名/xiaowo/xiaowo-order.apk # 期望 200 + 长度是真实的 APK 大小
   ```

5. 别开「强制下载附件」以外的花活，也别开防盗链（Referer 白名单会把 App 的请求挡掉 ——
   App 用 `User-Agent: XiaoWo-Updater`，没有 Referer）。

**自建服务器的 nginx 最小配置：**

```nginx
location /xiaowo/ {
    root /var/www;              # 文件真实位置 /var/www/xiaowo/
    autoindex off;

    # 补上 apk 的 MIME。用 types ，否则 nginx 的默认 MIME 表会被整块覆盖掉
    types {
        application/vnd.android.package-archive apk;
        text/html html;
        text/css css;
        application/javascript js;
        application/json json;
        image/png png;
        image/jpeg jpg jpeg;
        image/svg+xml svg;
        text/plain txt;
        application/octet-stream bin;
    }

    # 清单别被缓存住，否则可能永远拉到旧版本
    add_header Cache-Control "no-cache";
    # APK 本身内容随版本变化，也让它每次都回源
    add_header X-Content-Type-Options "nosniff";
}
```

> `types { }` 会**覆盖** nginx 内置的 MIME 表而不是追加 —— 所以上面把常用的几类一起写全了，
> 否则同目录下的 `.html`、`.css` 会变成 `application/octet-stream`。
> 只想追加、不想覆盖的话用 `include mime.types;` + 单独在 `location` 里对 `.apk` 用
> `default_type application/vnd.android.package-archive;`（但这会影响其他非 apk 文件，不推荐）。

> `Cache-Control: no-cache` 很重要：App 拉清单时带了 `Cache-Control: no-cache` 请求头，
> 但中间若有 CDN/反代强行缓存，还是可能拿到旧清单。发现「明明传了新版本 App 还说已是最新」，
> 先怀疑缓存。

---

## 6. 更新流程时序图

```
 手机(App)                静态存储(version.json + APK)            系统安装器
    │                              │                                │
    │ 1. 用户点「检查更新」          │                                │
    │─────────────────────────────>│                                │
    │   GET version.json           │                                │
    │<─────────────────────────────│ 200 + JSON                     │
    │                              │                                │
    │ 2. 取 role 对应的那段(order/recv)                               │
    │    比较 versionCode > 当前版本?                                 │
    │    ├─ 否 → 提示「已经是最新版」，结束                             │
    │    └─ 是 ↓                                                    │
    │                              │                                │
    │ 3. GET <url> 下载 APK         │                                │
    │─────────────────────────────>│                                │
    │<─────────────────────────────│ 200 + APK 字节流(带进度)         │
    │    存到 cacheDir/update-<vc>.apk                               │
    │                              │                                │
    │ 4. 有「安装未知应用」权限吗？                                    │
    │    ├─ 没有 → 跳系统设置页让用户开启，结束（回来后再点一次）        │
    │    └─ 有 ↓                                                     │
    │                              │                                │
    │ 5. FileProvider 转 content:// 并 ACTION_VIEW                    │
    │──────────────────────────────────────────────────────────────>│
    │                              │              弹出安装界面        │
    │                              │   用户点「安装」→ 覆盖安装(同签名) │
    │                              │                                │
    │ 6. 重新打开 App → 版本号已变，下次检查就会说「已是最新版」         │
```

**注意第 5 步的「同签名」**：覆盖安装要求新旧 APK 用**同一个密钥**签名。
本工程用的是仓库里的 `debug.keystore`（`build.sh` 里 `--ks debug.keystore`），
只要一直用这个 keystore，就能覆盖安装。换了 keystore 会出现「应用未安装 / 签名冲突」，
必须先卸载旧版 —— 而卸载会丢数据（`allowBackup=false`）。

---

## 7. 常见坑（按踩到概率排序）

### ① `raw.githubusercontent.com` 在国内可能连不上 🔴

这是最高频的失败原因。构建这套工具的环境里实测（同一台机器，连续 5 次）：

| 域名 | 结果 |
| --- | --- |
| `raw.githubusercontent.com` | `000` / `ERR`（连接被重置），5 次全失败 |
| `github.com` | 时通时不通（抓包看是 `Connection reset by peer`） |
| `github.io` | `301` 稳定（Pages 站点域名，正常） |
| `pages.github.com` | `200` 稳定 |

```bash
curl -I https://raw.githubusercontent.com/...
# curl: (35) Recv failure: Connection reset by peer
```

**结论**：

- **`raw.githubusercontent.com` 不要用**，国内基本拿不到。
- **`*.github.io`（Pages）是目前实测最稳的**，优先选方案 B。
- **GitHub Release 资产直链要注意跳转**：`github.com/USER/REPO/releases/download/...`
  会 **302 跳到 `release-assets.githubusercontent.com`**，真正传输发生在那台域名上。
  `Updater.download()` 里设了 `setInstanceFollowRedirects(true)`，跳转本身能跟；
  但如果 `release-assets.githubusercontent.com` 被墙，下载照样失败 ——
  这个域名在本机实测也是**时通时不通**（10 次里成功 3 次）。

  所以 Release 只适合「网络环境比较好」的情况。真要长期稳定，**方案 C（对象存储/自己的服务器）最靠谱**，
  国内访问快且不依赖 GitHub 的域名。

> 不要用来源不明的「GitHub 加速」公共反代 —— 它有能力篡改 APK，等于把安装包交给陌生人。
> 自己在 VPS 上做反代（并且校验证书）可以，但那就等于方案 C 了，直接用方案 C 更省事。

### ② 必须 HTTPS

`Updater` 走 `HttpURLConnection`，明文 HTTP 在很多网络环境下会被拦或降级；
另外 Android 9+ 默认禁明文（本项目清单里为了局域网调试开了 `usesCleartextTraffic="true"`，
**但这只是调试便利，不要依赖它传 APK**）。一律用 HTTPS。

### ③ APK 直链必须「点开就是下载」

不能是网盘分享页那种「提取码 → 点下载 → 跳转」的页面。判断标准很简单：

```bash
curl -sIL https://你的地址/xiaowo/xiaowo-order.apk | grep -E '^HTTP/|^content-type|^content-length'
```

必须是 **200**，`content-type` 是 `application/vnd.android.package-archive`（或至少不是 `text/html`），
`content-length` 和本地 APK 大小一致。如果是 `text/html` 或者返回个几 KB，那就是跳转页 —— 不行。

`Updater` 里的判断是 `code != 200` 就报错（`fetch()` 和 `download()` 都有这句），
而 `download()` 还开了 `setInstanceFollowRedirects(true)`：

- 302 **跳到真正的 APK 文件**（比如 Release 资产跳到 CDN）→ 能跟，最终拿到 200，OK；
- 302 跳到**登录页 / 网盘分享页** → 跟过去拿到的是 200 + HTML，看起来「成功」了，
  但存下来的是个网页，点安装会报「解析包错误」。所以一定要用上一条的 `curl -I` 人工确认。`

### ④ Android 需要「安装未知应用」权限

Android 8（API 26）起，装 APK 需要 `REQUEST_INSTALL_PACKAGES`（清单里已声明）+ 用户手动授权。
第一次更新时 App 会弹「需要安装未知应用权限」的对话框，点「去开启」跳到系统设置，
打开开关后**回到 App 再点一次「检查更新」**（下载下来的包会复用，不用重新下）。

每家手机的路径不太一样，找不到就去设置里搜「未知应用」/「安装未知应用」。

### ⑤ 版本号只增不减

`versionCode` 必须比手机上装的大才会提示更新。改了代码忘了改 `versionCode`，就会一直说「已是最新版」。
`versionName` 只影响显示，改它没用。

改完清单记得确认 APK 里真的是新号：

```bash
aapt dump badging build/xiaowo-order.apk | head -1
```

### ⑥ 清单被缓存

App 请求带了 `Cache-Control: no-cache`，但 CDN/反代/浏览器缓存仍可能插手。
换版本后如果 App 还说旧版本，先 `curl` 一下清单地址看返回的是不是新内容，
再在该地址上加 `?v=$(date +%s)` 之类试一次，或给 origin 加 `Cache-Control: no-cache`。

### ⑦ 换签名 = 装不上

覆盖安装要求同签名（见上面时序图第 5 步）。别随手删 `debug.keystore`。

### ⑧ 两个端的版本要同步

`order` 和 `recv` 各走各的 `versionCode`。只更新了一端，另一端就永远停在老版本。
`make_version_json.py` 遇到两端不一致会**警告**，看到警告先确认是不是故意的。

---

## 8. 常见问题

**Q：`dist/` 里的 `*.apk.idsig` 是什么？要传吗？**
不用。那是 `apksigner` 做 v4 签名产生的附带文件，App 用不到。生成脚本会顺手把它从 `dist/` 里删掉。

**Q：我想改更新说明怎么办？**
```bash
BASE_URL=https://你的地址/xiaowo NOTES="修了长按不震动的 bug" bash tools/publish.sh local
```
单端单独写用 `python3 tools/make_version_json.py --order-notes "..." --recv-notes "..."`。
说明会显示在「发现新版本 1.6：这里的内容，正在下载…」，建议不超过 20 个字，太长会被截断。

**Q：只想校验，不想重新打包？**
```bash
python3 tools/make_version_json.py --verify dist
```

**Q：打包太慢，想跳过 build.sh？**
```bash
SKIP_BUILD=1 bash tools/publish.sh local
```

**Q：更新地址存在哪？怎么清掉？**
存在 SharedPreferences `xiaowo` → `update_url`（见 `Updater.KEY_URL`），
在 App 里重新填一次「设置更新地址」就会覆盖。

**Q：能自动检查更新吗？**
目前是手动点「检查更新」。要做启动自动检查，在 `MainActivity` 里调一次 `Updater.check()` 即可 ——
但注意别在用户没准备好的时候弹安装界面。

---

## 9. 相关文件

| 文件 | 作用 |
| --- | --- |
| [`tools/make_version_json.py`](../tools/make_version_json.py) | 读 APK 版本 → 生成 `version.json` + 整理 `dist/` |
| [`tools/publish.sh`](../tools/publish.sh) | 一键发布（build → dist → 提示） |
| [`src/com/couple/xiaowo/core/Updater.java`](../src/com/couple/xiaowo/core/Updater.java) | App 端：拉清单、比对、下载、装 |
| [`src/com/couple/xiaowo/ui/UpdateCard.java`](../src/com/couple/xiaowo/ui/UpdateCard.java) | 「我」页面里的更新卡片（含设置更新地址） |
| [`build.sh`](../build.sh) | 打包，产物在 `build/` |
| [`AndroidManifest.xml.in`](../AndroidManifest.xml.in) | `versionCode` / `versionName` 在这里改 |

```bash
# 两个工具的帮助
python3 tools/make_version_json.py --help
bash tools/publish.sh --help
```
