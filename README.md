# MGHttpdProvider — Minecraft 客户端共享 HTTP 服务（Fabric / Minecraft 26.2）

在 `127.0.0.1:3420` 上起**一个** HTTP 服务，供多个客户端模组挂载各自的路径前缀：

| 前缀 | 模组 | 内容 |
| --- | --- | --- |
| `/ctl` | [mcctl](https://github.com/MineAgent/mcctl) | 控制：按键 / 鼠标 / 视角 / 截图 / Baritone / 聊天 |
| `/aif` | [AdvancedInfoFetcher](https://github.com/MineAgent/AdvancedInfoFetcher) | 只读状态：坐标 / 背包 / 聊天 / 声音 / 世界 |

```
GET  :3420/           当前可用的 endpoint 列表（本服务自己提供）
GET  :3420/ctl/       mcctl 使用说明
POST :3420/ctl/       执行 mcctl 命令
GET  :3420/ctl/prtsc  当前帧 PNG
GET  :3420/ctl/mouse  当前光标位置
GET  :3420/aif/info   玩家状态
GET  :3420/aif/...    AdvancedInfoFetcher 的其它只读接口
```

`GET /` 只列出**当前真的挂载了**的 endpoint：没装 mcctl 就不会有 `/ctl` 那几行。

```bash
curl http://127.0.0.1:3420/
```

## 给模组用的 API

模组在自己的 `ClientModInitializer` 里注册一个前缀即可，服务由本模组负责起停：

```java
import com.example.httpd.HttpdProvider;

HttpdProvider.register(
        "/ctl",                                     // 路径前缀
        "mcctl — 客户端远程控制",                      // GET / 索引里显示的名字
        List.of(new HttpdProvider.Endpoint("GET", "/ctl/prtsc", "当前帧 PNG")),
        (exchange, path) -> {                       // path 是去掉前缀后的子路径
            // path == "/"      -> /ctl 或 /ctl/
            // path == "/prtsc" -> /ctl/prtsc
            ...
        });
```

* 只依赖 Fabric Loader，**不需要 Fabric API**；模组 jar 里用 `compileOnly` 依赖本模组的 API。
* 注册顺序无关紧要：服务在第一次 `register` 时启动，本模组的入口点也会确保它启动。
* `register` 时端口被占用（例如开了两个游戏）只会记一条 SEVERE 日志，不会让游戏崩。

## 退出时不再写崩溃报告

关游戏时渲染线程返回后，`Main` 会启动一个 post-main 看门狗：15 秒内 JVM 还没结束，它就写一份
`Client shutdown from post-main` 崩溃报告，然后 `System.exit(-8)`。而 JVM 只有**所有非 daemon 线程**都结束后
才会自己退出——`com.sun.net.httpserver` 的服务带一个非 daemon 的 `HTTP-Dispatcher` 线程，
Baritone 之类的模组也留着非 daemon 的 worker pool。JVM 关闭钩子救不了这个场景：
JVM 根本没开始关闭，钩子不会执行。

`ClientExitWatcher` 在渲染线程（`Minecraft#getRunningThread()`）上 `join()`，线程结束后先停掉 HTTP 服务
（`HttpServer#stop(0)`），再显式 `System.exit(0)`。关闭钩子照常执行（Minecraft 自己的那个也在内），
所以看门狗永远不会触发；这时世界早已保存、窗口早已关闭（`exitWorldAndClose()` 在 `main()` 返回前就跑完了），
强制退出不会丢存档。因为服务只有这一个，这段逻辑也就只需要一处。

实测（Minecraft 26.2 + Baritone + AdvancedInfoFetcher，关窗口后）：进程退出码 `0`，
`crash-reports/` 不新增文件；改之前 15 秒后必现 `Client shutdown from post-main`（退出码 `-8`）。

## 构建 / 安装

需要 JDK 25（Minecraft 26.2 要求）。26.1 起官方代码不再混淆，所以 Loom 不需要 mappings 配置。

```bash
./gradlew build      # 产物: build/libs/httpdprovider-1.0.jar
```

把 jar 放进 `.minecraft/mods/`，再放上要用的模组（mcctl / AdvancedInfoFetcher）。
启动后日志里会有：

```
MGHttpdProvider listening on http://127.0.0.1:3420
registered /ctl (mcctl — 客户端远程控制 (按键/鼠标/视角/截图/Baritone))
registered /aif (AdvancedInfoFetcher — 只读状态 (坐标/背包/聊天/声音/世界))
```

mcctl 和 AdvancedInfoFetcher 通过 `fabric.mod.json` 的 `depends` 依赖本模组（`httpdprovider >= 1.0`），
只装它们、不装本模组时 Fabric Loader 会直接报缺少依赖。

## 目录

```
src/main/java/com/example/httpd/
  HttpdProviderMod.java   Fabric 客户端入口, 启动服务 + 装上退出处理
  HttpdProvider.java      服务 / 前缀路由 / GET / 索引 / 注册 API
  PathHandler.java        模组实现的接口 (exchange, 去掉前缀的子路径)
  ClientExitWatcher.java  守候渲染线程, 客户端退出后停服务并结束 JVM
tools/VerifyProvider.java 脱离游戏验证路由 + 索引 (两个假前缀)
```

脱离游戏验证路由层（不需要 Minecraft，退出监听只在入口点里碰）：

```bash
javac --release 25 -encoding UTF-8 -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyProvider.java
java -cp /tmp/httpd-verify VerifyProvider
curl http://127.0.0.1:3420/           # 索引
curl http://127.0.0.1:3420/one/echo   # 前缀分发
curl http://127.0.0.1:3420/two/widgets
```

## 安全说明

服务只绑定 `127.0.0.1`，仅本机可访问；没有鉴权（本机任何时候都能控制游戏），
如果不需要请删除模组或关闭游戏。

## 许可证

LGPL-3.0-only：完整文本见 [`LICENSE`](LICENSE)，其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)。
源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
