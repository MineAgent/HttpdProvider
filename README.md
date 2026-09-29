# MGHttpdProvider — Minecraft 客户端共享 HTTP 服务（Fabric / Minecraft 26.2）

在 `127.0.0.1:3420` 上起**一个** HTTP 服务，供多个客户端模组挂载各自的路径前缀：

| 前缀 | 模组 | 内容 |
| --- | --- | --- |
| `/ctl` | [mcctl](https://github.com/MineAgent/mcctl) | 控制：按键 / 鼠标 / 视角 / 截图 / Baritone / 聊天 |
| `/aif` | [AdvancedInfoFetcher](https://github.com/MineAgent/AdvancedInfoFetcher) | 只读状态：坐标 / 背包 / 聊天 / 声音 / 世界 |
| `/op` | [Craft Command](https://github.com/MineAgent/cmdCraft) | 容器操作：合成 / 快捷栏 / 熔炉 / 箱子 / 转向 |

```
GET  :3420/           当前可用的 endpoint 列表（本服务自己提供）
GET  :3420/ctl/       mcctl 使用说明
POST :3420/ctl/       执行 mcctl 命令
GET  :3420/ctl/prtsc  当前帧 PNG
GET  :3420/ctl/mouse  当前光标位置
GET  :3420/aif/info   玩家状态
GET  :3420/aif/...    AdvancedInfoFetcher 的其它只读接口
GET  :3420/op/        Craft Command 使用说明
POST :3420/op/        执行一条容器操作命令（合成/背包/熔炉/箱子/转向）
```

`GET /` 只列出**当前真的挂载了**的 endpoint：没装 mcctl 就不会有 `/ctl` 那几行。

```bash
curl http://127.0.0.1:3420/
```

## 窗口标题里的端口（1.1.0）

服务正常起来后，游戏窗口标题会被追加 ` - 3420`：

```
Minecraft* 26.2 - 3420
```

这样一眼就能看出这个实例是不是**这一个**在提供 `127.0.0.1:3420`：端口被别的实例占着、本模组没起来时，
标题原样不动（`HttpdProvider.isRunning()` 为 false，`WindowTitle.decorate` 直接返回原字符串）。
多开时只有真正绑到端口的那一个带后缀，另一个实例看着自己的日志就知道是被占了。

实现上**没有任何平台分支**，也不需要 Fabric API：

* 写标题走的是 GLFW（`GLFW.glfwSetWindowTitle`，LWJGL 随 Minecraft 一起发布），
  Linux / Windows / macOS 都是同一份代码——不碰 `user32!SetWindowText`，也不碰 X11/Wayland。
* 光调用一次不够：Minecraft 每次进出世界都会重新算一遍标题（`Minecraft#updateTitle` → `Window#setTitle`），
  会把后缀盖掉。所以用一个 Mixin（`com.example.httpd.mixin.WindowTitleMixin`，
  Mixin 由 Fabric Loader 自带）在 `Window#setTitle` 的参数上做一次改写：**所有**标题设置都从这里过，
  后缀不会被覆盖，重复设置也不会叠加（已经以 `" - 3420"` 结尾就不再追加）。
* 窗口标题在入口点**之前**就算好了，窗口也是用它创建的（入口点跑在 `Minecraft` 构造器里，那时窗口还不存在），
  所以 `start()` 成功后会把一次 `updateTitle()` 排到渲染线程上（`WindowTitle.refresh()` → `Minecraft#execute`），
  在第一个 tick、窗口已经有时执行：标题界面就带后缀，不用等进世界。GLFW 要求窗口函数在主线程调用，
  这样也顺带满足了。

## 如何接入这个 lib（写自己的模组）

一句话：在自己的 `ClientModInitializer` 里调一次 `HttpdProvider.register(...)`，剩下的（起服务、
路由、`GET /` 索引、关游戏时的清理）都由 provider 负责。下面是完整步骤。

### 1. 拿到 API jar

API 只有两个类：`com.example.httpd.HttpdProvider`（注册入口 + `HttpdProvider.Endpoint`）和
`com.example.httpd.PathHandler`（你要实现的接口）。1.1.0 起 `HttpdProvider.isRunning()` 是 public 的
（服务是否正在监听；窗口标题那段逻辑用它判断要不要加后缀），注册与路由 API 未变。自己构建 provider：

```bash
git clone https://github.com/MineAgent/HttpdProvider
cd HttpdProvider && ./gradlew build
# -> build/libs/httpdprovider-1.1.0.jar
```

把 `httpdprovider-1.1.0.jar` 复制进你的仓库（惯例是 `libs/`），作为 **`compileOnly`** 依赖 ——
不要把 provider 打进你的 jar，Fabric Loader 会从 `.minecraft/mods/` 加载真正的那一份。

### 2. build.gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // 26.1+ 不混淆，不需要 mappings
    minecraft "com.mojang:minecraft:26.2"
    implementation "net.fabricmc:fabric-loader:0.19.5"

    // provider 的 API, 只编译时用
    compileOnly files('libs/httpdprovider-1.0.1.jar')
}
```

只依赖 Fabric Loader，**不需要 Fabric API**（provider 自己也不需要）。

### 3. fabric.mod.json 里声明依赖

```json
{
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~26.2",
		"java": ">=25",
		"httpdprovider": ">=1.0"
	}
}
```

写上 `httpdprovider` 之后，玩家只装你的模组、没装 provider 时 Fabric Loader 会直接报缺少依赖，
而不是运行到一半才发现没人监听 3420。

### 4. 入口点里注册一个前缀

一个模组用一个前缀，把前缀 / 名字 / 索引条目做成 endpoint 类的常量（三个现有模组都是这个写法）：

```java
package com.example.mymod;

import com.example.httpd.HttpdProvider;
import net.fabricmc.api.ClientModInitializer;

public final class MyMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HttpdProvider.register(MyEndpoint.PREFIX, MyEndpoint.NAME, MyEndpoint.ENDPOINTS, new MyEndpoint());
	}
}
```

```java
public static final String PREFIX = "/my";
public static final String NAME = "MyMod — 一句话说明";
public static final List<HttpdProvider.Endpoint> ENDPOINTS = List.of(
		new HttpdProvider.Endpoint("GET", "/my/", "使用说明"),
		new HttpdProvider.Endpoint("POST", "/my/", "执行命令 (text/plain, UTF-8)"));
```

* 端口是固定的 `127.0.0.1:3420`，不要自己传端口。
* 注册顺序无关紧要：服务在第一次 `register` 时启动，provider 的入口点也会确保它启动。
* `register` 时端口被占用（例如开了两个游戏）只会记一条 SEVERE 日志，不会让游戏崩。

### 5. 实现 PathHandler

```java
public final class MyEndpoint implements PathHandler {
	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			if (!"/".equals(path)) {
				respond(exchange, 404, "no endpoint at /my" + path + "\n\n" + Help.text());
				return;
			}

			switch (exchange.getRequestMethod()) {
				case "GET", "HEAD" -> respond(exchange, 200, Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> { /* Allow 头 + 204 */ }
				default -> { /* Allow 头 + 405 */ }
			}
		} catch (Exception e) {
			LOG.log(Level.WARNING, "request failed", e);
			respond(exchange, 500, "internal error: " + e + "\n");
		}
		// provider 在 handle 返回后关闭 exchange, 这里不要自己 close
	}
}
```

要点：

* `path` 是**去掉你的前缀之后**的子路径，总是以 `/` 开头：请求 `/my` 和 `/my/` 到这里都是 `"/"`，
  `/my/info` 到这里是 `"/info"`。
* **前缀按路径边界匹配**：只有 `/my` 本身和 `/my/...` 会到你这里。`/myinfo` **不是** `/my` 下的路径，
  provider 直接回 `404`，不会把它切成 `/info` 交给你。（`HttpServer` 的 context 匹配是裸字符串前缀，
  1.1.1 起 provider 在分发前自己挡掉了这种请求；别指望靠意外拼写兜住 endpoint。）
* 每次请求都由你写响应（`sendResponseHeaders` + body）；provider 只负责 `exchange.close()`。
* 你在 `handle` 里抛异常也不会让服务挂掉：provider 记一条日志并尝试回 500。不过响应头已经发出去时
  它写不了正文，所以最好还是自己 try/catch。

### 6. 线程规则（最容易踩的坑）

`handle` 跑在 provider 的 HTTP 线程池上（`httpd-http`，daemon），**不是**游戏线程：不能在这里直接
读游戏状态，更不能碰容器 / GUI。需要 `Minecraft` 状态时，把工作丢到客户端线程，HTTP 线程等结果：

```java
Minecraft client = Minecraft.getInstance();          // static, 任何线程都能读
if (client == null) { respond(exchange, 409, "client not running\n"); return; }

CompletableFuture<String> result = new CompletableFuture<>();
client.execute(() -> {                               // 在客户端(渲染)线程上执行
	try {
		result.complete(readGameState());
	} catch (Throwable t) {
		result.completeExceptionally(t);
	}
});

String body = result.get(3, TimeUnit.SECONDS);       // HTTP 线程阻塞等待
```

* `Minecraft#isSameThread()` 可以用来兼容"调用方本来就在游戏线程上"的情况。
* **一定要有超时**：客户端卡住或正在退出时，不能把 HTTP 线程永久挂死（`/aif` 用 3 秒，
  `/op` 的合成用大约 2 分钟）。
* 只读快照建议在客户端线程上一口气拼好字符串再回传；增量队列（聊天、声音这类）用 `synchronized`
  的缓冲区，让 HTTP 线程直接 drain，不必等下一个 tick（见 `/aif` 的 `LineBuffer`）。
* 需要多个 tick 才完成的任务（例如 `/op` 的合成）不要让 HTTP 线程空转：任务自己记住一个
  "完成回调 / latch"，由客户端 tick 驱动，完成时唤醒 HTTP 线程。

### 7. 不要自己起服务，也不要自己处理退出

* 服务只有 3420 这一份，由 provider 启动；你自己 `new HttpServer` 只会端口冲突。
* `com.sun.net.httpserver` 带非 daemon 线程，别的模组（Baritone 等）也会留非 daemon 线程。
  关游戏时如果不显式结束 JVM，Minecraft 的 post-main 看门狗 15 秒后会写一份
  `Client shutdown from post-main` 崩溃报告并 `System.exit(-8)`。这段逻辑在 provider 的
  `ClientExitWatcher` 里，全局只需要一处 —— 你的模组**不要**再装自己的看门狗。
* 只有确实持有需要释放的资源时（例如 mcctl 按着不放的按键、`/op` 里已经在跑的 tick 任务），
  才加一个 `Runtime.getRuntime().addShutdownHook(...)` 做清理；正常退出交给 provider。

### 8. 约定与建议

* **前缀**：短、全小写、以 `/` 开头，注意 `TrieMap` 是排好序的，`GET /` 索引按前缀字母序展示。
* **`GET <前缀>/`**：返回使用说明纯文本 —— 人、脚本、agent 都靠它，务必写。
* **`POST <前缀>/`**：请求体是纯文本命令（`text/plain; charset=utf-8`），响应正文也用纯文本。
* **状态码**：`200` 成功、`400` 参数/命令错误、`404` 前缀下没有该路径、`405` 方法不允许、
  `409` 客户端没启动/没进世界、`413` 请求体过大、`500` 内部错误、`504` 超时。
  让调用方只看状态码就能分支，正文给人看。
* **限制请求体**：边读边限长（`/ctl` 用 64KB，`/op` 用 16KB），超了回 `413`。
* **索引**：`ENDPOINTS` 里列的每一行都会出现在 `GET /` 里，别漏；也别列没实现的路径。
* **别名路径**：给常用 endpoint 加 `.txt` / 简写别名（如 `/aif/inv`、`/ctl/mouse.txt`）很便宜，
  手工 curl 时很省事。
* **不要缓存**：provider 在把请求交给 handler **之前**就设好 `Cache-Control: no-store`，所以每个响应
  （包括 `404` / `405` 这些它自己回的）都带；你自己再设一遍也无害。

### 9. 现有实现可以抄

| 模组 | 前缀 | 入口 / endpoint | 值得参考的地方 |
| --- | --- | --- | --- |
| mcctl | `/ctl` | `McCtlClientMod` / `ControlEndpoint` | POST 命令 + JSON 回执、同步等待、PNG 与光标等二进制/只读 GET |
| AdvancedInfoFetcher | `/aif` | `AdvancedInfoFetchMod` / `InfoEndpoint` | 只读 GET、`CompletableFuture` hop 到客户端线程、增量队列 drain |
| Craft Command | `/op` | `CraftCmdMod` / `OpEndpoint` | 复用 Brigadier 命令树、异步任务完成后才回响应 |

脱离游戏先验证路由、索引和前缀边界（不需要 Minecraft）：

```bash
# provider 用 log4j2 记日志；脱离游戏跑时把 Minecraft 自带的那份 log4j-api 放进 classpath 即可
# （没有 log4j-core，log4j 会退回到 SimpleLogger，stderr 上有一行
#  "Log4j API could not find a logging provider." 属正常，provider 那几行不打印，
#  但下面这些结果照常在 stdout）。
LOG4J_API=$(ls ~/.minecraft/libraries/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar | head -1)

javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyProvider.java
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider          # 自检，全过才退 0
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider --serve  # 保持服务，手动 curl
```

退出（看门狗）也能脱离游戏验证：

```bash
javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyExit.java
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyExit
```

（你自己的 endpoint 如果依赖 Minecraft，可以像 mcctl 的 `tools/LoaderSmokeTest.java` 那样，
用真实的 Minecraft 运行时 classpath 起一个"没有游戏"的 JVM 来加载入口点并 curl。）

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

1.0.1 起还有两处细节：

* **退出处理不依赖端口绑定成功**：`ClientExitWatcher.onClientExit(...)` 与关闭钩子在 `HttpdProvider.start()`
  **之前**安装，端口被占用（第二个游戏实例）时也照常生效——那种情况下本模组自己没有服务，但「JVM 退不掉」
  是**别的模组**的非 daemon 线程造成的（Baritone 的 worker pool），看门狗照样会开火。
  `HttpdProvider::stop` 是幂等的，服务没起来时 teardown 什么也不做。
* **不掩盖真正的失败**：如果 JVM 已经在关闭中（崩溃、`SIGTERM` 等会执行关闭钩子），守候线程只做 teardown，
  不再 `System.exit`，以免把那次失败的退出码覆盖成 `0`。

实测（Minecraft 26.2 + Baritone，关窗口后）：

| 场景 | 退出码 | `crash-reports/` |
| --- | --- | --- |
| 正常（端口空闲），1.0 及以前 | `0` | 不新增 |
| 端口被占用，**1.0** | **`-8`** | **新增 `Client shutdown from post-main`** |
| 端口被占用，**1.0.1** | `0` | 不新增 |

（端口被占用用 `TestSave.sh` 启动前先占住 `127.0.0.1:3420` 复现；`mods/` 里放了 Baritone。
1.0.1 那次日志是 `client exited, stopping the HTTP server` → `exiting the JVM so the post-main shutdown
watchdog cannot fire`。）

不需要启动客户端也能验证这套线程逻辑（`tools/VerifyExit.java`）：

```bash
javac --release 25 -encoding UTF-8 -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyExit.java
java -cp /tmp/httpd-verify VerifyExit     # 全部 ok 才退 0
```

它检查：JDK 确实会留下非 daemon 的 `HTTP-Dispatcher` 线程（危险是真的）、provider 自己的线程池是 daemon、
`HttpdProvider.stop()` 能消掉那个非 daemon 线程且可重复调用、服务没起来时 `stop()` 也无害。

## 构建 / 安装

需要 JDK 25（Minecraft 26.2 要求）。26.1 起官方代码不再混淆，所以 Loom 不需要 mappings 配置。

```bash
./gradlew build      # 产物: build/libs/httpdprovider-1.1.1.jar
```

把 jar 放进 `.minecraft/mods/`，再放上要用的模组（mcctl / AdvancedInfoFetcher / Craft Command）。
启动后日志里会有：

```
[00:20:14] [Render thread/INFO]: MGHttpdProvider listening on http://127.0.0.1:3420
[00:20:14] [Render thread/INFO]: registered /aif (AdvancedInfoFetcher — 只读状态 (坐标/背包/聊天/声音/世界))
[00:20:14] [Render thread/INFO]: registered /ctl (mcctl — 客户端远程控制 (按键/鼠标/视角/截图/Baritone))
[00:20:14] [Render thread/INFO]: registered /op (Craft Command — 客户端容器操作 (合成/背包/熔炉/箱子/转向))
[00:20:18] [Render thread/INFO]: window title is now "Minecraft* 26.2 - 3420"
```

最后一行是 1.1.0 加的，它读回来的正是交给 GLFW 的那份标题。

### 日志去哪了

provider 用游戏自带的 log4j2（`LogManager.getLogger("httpd")`；log4j-api 随 Minecraft 一起来，
`build.gradle` 不用加依赖），所以这几行经过 Minecraft 的根 logger，**同时进控制台 stdout 和
`logs/latest.log`**。1.1.0 及以前用的是 `java.util.logging`：它的默认 handler 只写 stderr，又不经过
log4j，于是控制台和 latest.log 里都看不到"到底注册了哪几个前缀"——排查"模组挂上没有"时很别扭，
1.1.1 换掉了。

mcctl、AdvancedInfoFetcher 和 Craft Command 都通过 `fabric.mod.json` 的 `depends` 依赖本模组
（`httpdprovider >= 1.0`），只装它们、不装本模组时 Fabric Loader 会直接报缺少依赖。

## 目录

```
src/main/java/com/example/httpd/
  HttpdProviderMod.java   Fabric 客户端入口, 启动服务 + 装上退出处理 (+ 窗口标题后缀)
  HttpdProvider.java      服务 / 前缀路由 / GET / 索引 / 注册 API / isRunning()
  PathHandler.java        模组实现的接口 (exchange, 去掉前缀的子路径)
  ClientExitWatcher.java  守候渲染线程, 客户端退出后停服务并结束 JVM
  WindowTitle.java        窗口标题后缀 (GLFW, 无平台分支) + 启动后刷一次标题
  mixin/WindowTitleMixin.java  Window#setTitle 的参数改写 (标题每次变化都带上后缀)
src/main/resources/
  fabric.mod.json         模组元数据 (entrypoints / mixins / depends)
  httpdprovider.mixins.json   Mixin 配置 (client 侧 WindowTitleMixin)
tools/VerifyProvider.java 脱离游戏自检路由/索引/前缀边界/no-store (--serve 可手动 curl)
tools/VerifyExit.java     脱离游戏验证退出/看门狗 (线程 daemon 属性 + teardown)
```

脱离游戏验证路由层（不需要 Minecraft，退出监听只在入口点里碰）：

```bash
LOG4J_API=$(ls ~/.minecraft/libraries/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar | head -1)

javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyProvider.java
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider          # 自检路由/索引/前缀边界/no-store
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyProvider --serve  # 保持服务，手动 curl
curl http://127.0.0.1:3420/           # 索引
curl http://127.0.0.1:3420/one/echo   # 前缀分发
curl http://127.0.0.1:3420/oneside    # 404：/one 的兄弟路径不属于 /one
```

退出（看门狗）也能脱离游戏验证：

```bash
javac --release 25 -encoding UTF-8 -cp "$LOG4J_API" -d /tmp/httpd-verify \
  src/main/java/com/example/httpd/{HttpdProvider,PathHandler}.java tools/VerifyExit.java
java -cp "/tmp/httpd-verify:$LOG4J_API" VerifyExit
```

## 安全说明

服务只绑定 `127.0.0.1`，仅本机可访问；没有鉴权（本机任何时候都能控制游戏），
如果不需要请删除模组或关闭游戏。

## 版本

* **1.1.1** — 三处"代码和文档/直觉不一致"的地方。① `dispatch` 在交给 handler 前统一设
  `Cache-Control: no-store`：1.1.0 其实只有 provider 自己的响应带，模组自己写的响应没有（README
  却承诺了）；② 前缀改成**按路径边界匹配**：`HttpServer` 的 context 是裸字符串前缀匹配，`/aifinfo`
  会被塞进 `/aif` 再切成 `/info`，等于用一个意外拼写调通了 `/aif/info`——现在直接 `404`；③ 日志从
  `java.util.logging`（只写 stderr、进不了 latest.log）换成游戏自带的 log4j2，注册 / 监听 / 标题
  这几行**同时进 stdout 和 `logs/latest.log`**。注册与路由 API 未变，依赖方（`depends httpdprovider
  >= 1.0`）照旧可用，不必重新 vendored 那个 compileOnly jar。`tools/VerifyProvider.java` 也从
  "起服务等 curl" 变成自检 + `--serve`。
* **1.1.0** — 窗口标题带上端口：服务正常起来后标题追加 ` - 3420`，一眼看出哪个实例在提供
  `127.0.0.1:3420`。用 GLFW + 一个 `Window#setTitle` 的 Mixin 实现，**没有平台分支**，也不需要
  Fabric API；端口没绑上（第二个实例）时标题不动。`HttpdProvider.isRunning()` 变成 public，
  注册 / 路由 API 未变，依赖方（`depends httpdprovider >= 1.0`）照旧可用。
* **1.0.1** — 退出处理的两处加固：在 `start()` 之前安装（端口被占用也能干净退出，1.0 时会写
  `Client shutdown from post-main` 崩溃报告并 `System.exit(-8)`）；已经在关闭中时不再抢着
  `System.exit`，不覆盖真正的失败退出码。新增脱离游戏的 `tools/VerifyExit.java`。API 未变，
  依赖方（`depends httpdprovider >= 1.0`）照旧可用，不必重新 vendored 那个 compileOnly jar。
* **1.0** — 第一个版本：`127.0.0.1:3420` 上的共享 HTTP 服务、前缀路由、`GET /` 索引、
  `ClientExitWatcher` 退出处理。

## 许可证

LGPL-3.0-only：完整文本见 [`LICENSE`](LICENSE)，其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)。
源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
