# 「软件包无效」的根因与修复

## 现象

App 内自动更新下载完成后，系统安装器提示 **「软件包无效」**，装不上。

## 根因（已复现）

三处叠加，任何一处都足以导致失败：

### 1. 缓存复用只看大小，不看完整性

`Updater.download()` 原来：

```java
if (out.exists() && out.length() > 1024 * 512) {
    cb.onDone(out);   // 直接拿去安装
    return;
}
```

只要缓存里有个 **>512KB 的残包**就当成"已下载好"。而下载中断（切后台、
断网、系统回收）恰恰最常留下这种残包 —— 于是之后**每次**更新都会拿它去装，
永远报「软件包无效」，而且用户完全不知道为什么。

### 2. 下载完成不校验长度

`downloadOnce()` 写完就直接 `return null`（成功）。但网络在
`in.read(buf)` 里提前返回 `-1` 时**不会抛异常**，循环正常退出，
留下一个被截断的文件 —— 代码认为下载成功了。

### 3. 弱校验拦不住截断

第一版修复我用了"检查 ZIP 魔数 `PK\x03\x04`"。实测证明这没用：

```
原始 771427 字节 → 截成 600000 字节
头四字节仍然是 PK\x03\x04     ← 弱校验照样放行
apksigner: Malformed APK: not a ZIP archive
```

**被截断的 zip 丢的是文件尾部的中央目录**，所以检查必须落在尾部。

## 修复

### 完整性判据：查尾部 EOCD

```java
// ZIP 的 End Of Central Directory：签名 50 4B 05 06
// 位置在文件末尾 22 ~ 22+65535 字节内
private static boolean hasZipCentralDirectory(File f) {
    // 读文件尾部最后 22+65535 字节，从后往前找 EOCD 签名
}
```

纯 Java 实现（`RandomAccessFile`），不引第三方库。

实测：

```
PASS   /build/xiaowo-order.apk   (771427 bytes)
PASS   /build/xiaowo-recv.apk    (6313469 bytes)
REJECT /tmp/broken.apk           (614400 bytes)   ← 截断包被拦下
```

### 三道闸

| 位置 | 检查 |
|---|---|
| 缓存复用前 | `length == 声明 size` **且** EOCD 存在；不合格就删掉重下 |
| 下载写完后 | 核对长度，不符则删除并抛「安装包不完整（xxxKB），已删除，请重试」 |
| 交给安装器前 | 再验一次 EOCD，不过就删掉并提示「安装包已损坏，请重新下载」 |

### 清掉历史残包

老版本留下的残包在新版本里还会被复用一次（因为文件名相同）。
所以 `MainActivity.onCreate` 调一次 `Updater.purgeStaleDownloads()`：
首个新版启动时删掉 `cacheDir` 下所有 `.apk`，标记后不再重复。

### 声明 size

`dist/version.json` 现在同时写顶层 `size` 和按角色的 `order`/`recv` 段：

```json
{
  "versionCode": 33,
  "versionName": "3.7.5",
  "order": { "versionCode": 33, "versionName": "3.7.5", "size": 775523 },
  "recv":  { "versionCode": 33, "versionName": "3.7.5", "size": 6317565 },
  "size": 775523
}
```

GitHub 那条路本身就有 size（release asset 的 `size` 字段），所以一直可校验。

## 验证记录

**服务端包是好的**（排除"上传坏了"这个可能）：

```
GET /repos/Grape-LSXG/xiaowo/releases/assets/665049632
→ http=200  size=775523

sha256 本地构建  : 48ba9ed29bd59e0dc5fb049fcd895c5f7555efac7c1bae156be79736a61015cd
sha256 GitHub    : 48ba9ed29bd59e0dc5fb049fcd895c5f7555efac7c1bae156be79736a61015cd  ✓

apksigner verify → 通过
zip 条目 20，testzip → None（无损坏）
```

**真机链路**：点「检查更新」→ 下载 → 系统安装器弹出
**「指纹验证后安装」**，不再出现「软件包无效」。指纹校验是 vivo 对侧载应用的
安全策略，与包完整性无关 —— 走到这一步说明包已经被系统受理。

## 教训

「下载成功」和「文件完整」是两件事。HTTP 的 `read()` 返回 -1 既可能是
正常结束，也可能是连接被掐断，两者从流的角度无法区分，
**必须拿声明的长度或结构特征去核对**。

而缓存复用的判据，永远不能只看"大小够不够"。
