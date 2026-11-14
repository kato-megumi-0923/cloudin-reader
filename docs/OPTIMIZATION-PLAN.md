# 云中阅读 · 优化方案

**基线版本**：0.2.21 / versionCode 123（commit `8b791e7`）
**文档版本**：v3 · 2026-11-13（按作者第二轮复核意见修订）
**范围**：`app/src/main/java/com/cloudin/reader/` 全量走读（51 个 Kotlin 文件 / 12332 行）+ 构建配置、清单、测试、发版清单
**方法**：静态代码走读 + 项目工作日志中的历史实测数据。**本轮未编译、未跑 benchmark、未装包**，所有「预期收益」均为假设，须按 §7 验证后才能定性。

> **v3 修订摘要**：① 备份合法性改为**结构校验**；条目数骤降降级为**诊断信号**（批量删书是合法骤降，不能当硬门禁）；恢复时逐条**校验书籍正文文件是否还在**，正文缺失的条目剔除或标记失效；新增「备份后批量删书，再恢复」验收。② 分组自愈加**前置条件**：分组读取失败时保留书籍归属、不落盘，等读取成功后下一次写入再落盘。③ 记下两条**留到对应阶段**的实现细节：分页缓存命中要刷新访问时间；异步搜索必须独立于分页执行器。
>
> **v2 修订摘要（保留）**：P0 恢复方案重写（三态 + 失败不写 + 独立校验备份，删去错误的「读 `.bak` 恢复」）；分页缓存验收收敛为「切回用过的排版命中」；TXT 解码改测量后决策、采样需全文校验或回退；搜索后台化为主项、章节二分随附；玻璃/书架刷新先测掉帧；DiffUtil 需不可变快照；移除配对码；改正回滚方式（`install -r` 不能降级）与日常装包约定。

---

## 0. 需求锁定与范围声明

本轮为**只读评估 + 方案交付**，不改源码、不碰书库、不动平板。因交付物明确且无不可逆操作，按 Q1 处理，不做阻塞式质询。

| 槽位 | 取值 | 状态 |
|---|---|---|
| S1 目标 | 给出一份按优先级排序、可直接排期的优化方案 | 【已锁定】用户原话 |
| S2 边界 | 含性能、内存、数据安全、工程效率；**不含**新功能、UI 重设计、第三方库替换、**局域网传书配对码** | 【已锁定】v2 明确 |
| S3 验收 | 每条优化项带「文件:行号 + 改动要点 + 可执行验证用例」；落地时按 §7 分级验证 | 【已锁定】方案自带判据 |
| S4 约束 | 沿用现有验证约定（见 §7）；平板 OPD2409 只允许 `install -r` + 截图；不卸载、不在平板跑测试 | 【已锁定】项目约定 |
| S5 数据状态 | 平板已装 0.2.21，用户书库含 99+ 本书；不得触发数据迁移或重排 | 【已锁定】项目日志 |
| S6 风险回滚 | 代码改动按 Wave 分批回退；**发版后的回退用「旧代码 + 更高 versionCode + 同签名」修复包**，`install -r` 装回旧包不能降级 | 【已锁定】v2 修正 |
| S7 装包 | 日常测试直接用 Gradle debug 输出；`dist/` 只留发版包 | 【已锁定】v2 新增 |

---

## 1. 现状快照

| 维度 | 现状 | 证据 |
|---|---|---|
| 代码规模 | 12332 行 / 51 个 `.kt`；`ReaderActivity` 1956 行、`MainActivity` 1543 行，两个 Activity 占 28% | `wc -l` |
| 架构 | 无 ViewModel、无协程、无 DI；裸 `Thread` + `runOnUiThread`（MainActivity 7 处）、`Handler` + `ExecutorService` | `MainActivity.kt:1311,1337,1419`；`PageView.kt:470-471` |
| 大书表现 | 《佛本是道》3.8 MB / 2112 页，首次分页约 10~15 s，之后翻页正常；**「打开到首屏」与「首次分页」耗时未分别测量** | 项目日志 2026-11-13（**历史实测，非本轮**） |
| 分页 | ≥100000 字符走后台单线程分页 + 磁盘缓存；页布局 `LruCache(4)`；**分页执行器是单线程** | `PageView.kt:186,306-321,469-471`；`Paginator.kt:29` |
| 分页缓存 | 每本书**只存一份** `book-<id>.bin`，key 含尺寸/字号/行距/字体/系统指纹 | `PaginationCache.kt:13,15-19` |
| 搜索 | 主线程扫全文，最多 500 命中、界面渲染 100 条 × 2 个 TextView | `ReaderActivity.kt:1480-1558` |
| 玻璃 | AGSL 路径走 GPU `RenderEffect`；`GlassBar`/`GlassCardView` 走主线程 CPU 盒式模糊；**掉帧未量化** | `AgslGlassView.kt:217-224`；`GlassBar.kt:93-123`；`Blur.kt:8-19` |
| 持久化 | 三份 JSON：书库 / 书签用 `AtomicFile`，分组用 `writeText`；**三处读取失败都静默返回空集合** | `BookStore.kt:101-103`；`BookmarkStore.kt:113-115`；`GroupStore.kt:35-37,49` |
| 跨文件一致性 | 删除分组是「先写分组文件 → 再清书籍归属」两步，UI 另有「逐本删书 → 再删组」路径 | `GroupStore.kt:93-98`；`MainActivity.kt:848-852` |
| 书库与磁盘一致性 | `BookStore.remove()` **先 `save()` 更新书库、再 `deleteFiles()` 删正文与封面**；备份可能停在「删除前」，恢复旧备份会列出正文已不存在的书 | `BookStore.kt:305-319` |
| `AtomicFile` 语义 | `openRead()` 只在遗留 `.bak` 存在时改名回主文件；`startWrite()/finishWrite()` 用 `.new` 原子替换，**成功写入不保留旧版本** | AndroidX `core/util/AtomicFile.java`（`startWrite` / `finishWrite` / `openRead`） |
| 包体 | release APK 15 MB（debug 17 MB）；`isMinifyEnabled = false`、无 `shrinkResources`、无 lint/CI 配置 | `app/build.gradle.kts:19-21`；`dist/cloudin-reader-0.2.21.apk` |
| 测试 | 30 个 instrumented 测试（6 + 24），**0 个 JVM 单元测试** | `app/src/androidTest/` |
| 权限 | `MANAGE_EXTERNAL_STORAGE` + `INTERNET`；`SearchActivity` `exported=true` 但无 intent-filter | `AndroidManifest.xml` |

**做得好的地方**（保持，不要顺手改）：分页引擎自己写了中断检查与代次取消（`PageBreaker.kt:32`、`PageView.kt:324-328`）；缓存都有容量上限（`LruCache(4)`、封面 24 MB）；EPUB 导入走 128 KB 流式复制；`GroupStore.groupImportedBooks` 已有失败删组的补偿（`:71-78`）；局域网传书已有 Origin 校验、自定义头、文件名与扩展名白名单、上传大小上限、绑定具体 WiFi 接口 IP（`WifiBookTransfer.kt:96-111,259-262`）。

---

## 2. 优先级矩阵

| # | 优化项 | 级别 | 用户可感知收益 | 改动面 | 风险 |
|---|---|---|---|---|---|
| P0-1 | 三态 + 失败不写 + 独立校验备份（含**正文存在性校验**） | 数据安全 | 书库不被空列表覆盖；恢复后不出现「打不开的书」 | 1 文件 + 新增备份类 | 中 |
| P0-2 | 分组 / 书签同样处理静默失败与非原子写 | 数据安全 | 分组、书签不再被一次操作抹掉 | 2 文件 | 低 |
| P0-3 | 删除分组顺序 + **自愈以分组读取成功为前提** | 数据安全 | 失败不残留悬空 `groupId`；读盘异常不清空归属 | 2 文件 | 中 |
| P1-1 | 多版本分页缓存 | 体验 | 切回用过的排版不再重算 10~15 s | 2 文件 | 中 |
| P1-2 | TXT 解码（**测量后决策**） | 体验/稳定 | 待「打开到首屏」耗时确认后才排期 | 2 文件 | 中 |
| P1-3 | 全文搜索后台化（含章节二分） | 体验 | 搜大书不掉帧、能取消、不与排版排队 | 3 文件 | 中 |
| P1-4 | 玻璃路径（**先测掉帧再选**） | 体验 | 待数据决定改哪条路径 | 待定 | 待定 |
| P1-5 | 书架刷新（**先测掉帧再选**；若用 DiffUtil 需不可变快照） | 体验 | 待数据决定是否值得改 | 待定 | 待定 |
| P2-1 | 抽纯逻辑 + 统一执行器/lifecycle | 工程 | 后续改动更快 | 渐进 | 中 |
| P2-2 | 补 JVM 单测 + 固定性能基准 | 工程 | 回归不再靠人肉 | 新增 | 低 |
| P2-3 | R8 / 资源压缩 / lint | 工程 | 包体下降 | 1 文件 | 中 |
| P2-4 | 权限收敛（**不做配对码**） | 合规 | 上架兼容性 | 1 文件 | 低 |

**排序原则**：先堵数据丢失，再用**测量**决定性能项的取舍与顺序，最后做工程化。不做一次性 MVVM 重写（`Karpathy`：删除 > 重组，简化优先）。

---

## 3. P0 · 数据安全（建议下一个版本就带上）

### P0-1 书库：三态区分、失败即禁止写入、独立校验备份

**位置**：`BookStore.kt:68-106`（`load`）、`:140-171`（`save` 全量覆盖）、`:174-211`、`:280-299`（`import`）、`:304-338`（`remove` / `cleanupDuplicates` / `missingHashes`）、`:312-319`（`deleteFiles`）

**证据**：`load()` 在解析异常时 `return mutableListOf()`（`:101-103`），不区分「文件不存在」与「读取失败」。所有写入口都是「`load()` → 改 → `save()`」：置顶 `setPinned`（`:181-187`）、分组 `setGroup`（`:189-195`）/ `clearGroup`（`:197-202`）、导入 `import` 的重复与新增两条分支（`:286,292`）、`remove`（`:305-308`）、`cleanupDuplicates`（`:325-338`），连只做查询的 `missingHashes`（`:322-323`）也可能触发后续写入。文件一旦损坏，一次置顶/分组/导入就可能把**空列表**写回。

**证伪检查**：`updateProgress` 因 `firstOrNull` 找不到会 `return`，不会写回；但用户点名的置顶、分组、导入三条都会写回。结论成立，属**条件触发**，非必现。

**关于 `.bak` 的更正（v1 写错，此处按 AndroidX 源码修正）**：
- `AtomicFile.openRead()` 只在遗留 `.bak` 存在时把它 rename 回主文件，这是为兼容**旧实现**创建的备份，不是正常写入的产物。
- 现行 `startWrite()` 写 `library.json.new`，`finishWrite()` 把 `.new` 原子 rename 到主文件。**成功写入后磁盘上只有新版本，没有任何可随时恢复的旧版本。**
- 所以「启动时读 `.bak` 恢复」这条路不存在；且 `openRead()` 自己已经处理遗留 `.bak`，业务层再读一次是无意义甚至有害的。
- **要支持恢复，必须自己另外维护一份经过校验的备份。**

**方案**：
1. `load()` 返回显式三态，禁止用空列表代表失败：
   ```kotlin
   sealed interface LoadResult {
       data class Ok(val books: MutableList<Book>) : LoadResult
       data object Missing : LoadResult          // 文件不存在：合法的空书库
       data object Failed : LoadResult           // 读取/解析失败：只读，禁止写入
   }
   ```
2. **所有写入口统一前置校验**：`setFavorite / setPinned / setGroup / clearGroup / updateProgress / import / remove / cleanupDuplicates` 在 `Failed` 时只上报错误、不写盘。`Missing` 才允许创建空库。建议把「load → 校验 → 写」收敛到一个私有的 `mutate()` 入口，避免以后新增方法时漏掉。
3. **独立校验备份**（新增，例如 `files/library.backup.json`）：
   - **合法性靠结构校验，不靠条目数**：JSON 可解析；每个条目 `id / title / format / path` 齐备且类型正确；`id` 唯一；`format` 只允许 `txt|epub`；`path` 落在应用私有目录内；`progress` / 时间戳在合理范围。结构不合法 → 备份不可采纳。
   - **条目数骤降只是诊断信号，不是硬门禁**：批量删书本来就会让条目数骤降，这是合法操作；把它当拒绝条件会误判。做法是记录诊断（前后条目数、时间、触发来源），骤降时额外做一次正文存在性抽查、并要求更强的人工确认，而不是直接判「备份损坏」。
   - **恢复时必须校验书籍文件是否还在**：`BookStore.remove()` 是先 `save()`（`:308`）再 `deleteFiles()`（`:309`），所以「删除前生成的备份」即使结构完全合法，也可能把已删除的书重新列出来，点进去打不开。恢复流程逐条检查正文：TXT 看 `File(path).isFile()`，EPUB 看解压目录存在且关键文件可读；**正文缺失的条目一律剔除或标记为失效，不得原样写回书架**，并在恢复报告里列出被剔除的书名 / id。
   - 备份本身也用 `AtomicFile` 写，避免备份自己损坏；写入前先确认主文件当前内容是 `Ok`。
   - 恢复流程：`Failed` → 保留现场（复制为 `library.corrupt.<时间戳>.json`）→ 读备份做结构校验 → 逐条做正文存在性校验 → 用 `AtomicFile` 写入**过滤后**的结果 → 否则保持只读并提示用户，绝不猜。
   - 备份更新时机选「每次成功 `save` 之后」或「应用启动加载成功时」，二选一即可，不要每次读都写。
4. 其余 JSON 存储按同一套「失败不写」规则处理（见 P0-2）；备份能力**书库必须有**，分组建议有，书签按其自身「数据可丢」的定位可只做失败不写 + 保留损坏现场（见 `BookmarkStore.kt:20` 注释）。

**验证**（按 §7「数据格式」级别）：
- 坏 JSON + 分别调 `setPinned` / `setGroup` / `import` → 断言三者的文件内容都**未被改写**、无 `[]` 覆盖、损坏副本存在。
- 正常书库 → 制造坏主文件 → 从**独立备份**恢复 → 断言结构校验通过、恢复后**每条书的正文都存在**、内容可解析；把备份也写坏 → 断言不覆盖主文件、保持只读。
- **备份后批量删书，再恢复**：生成备份 → 批量删书（书库更新 + 正文 / 封面删除）→ 制造主文件损坏 → 恢复 → 断言已删书**不会重新出现在书架**（被识别为正文缺失并剔除 / 标记）、保留的书都能打开、恢复报告列出被剔除条目。这条专门覆盖 `BookStore.remove():305-309` 的时序。
- 条目数骤降只作诊断：批量删书产生的合法骤降不得被判为「备份损坏」而拒绝恢复；备份自身结构不合法时则不得采纳。
- 只跑相关几条 `am instrument -e class ...#方法`，不必全量。

**代码回退**：新增备份文件可随时停写，主逻辑单文件回退；不涉及已有数据结构变更。（发版后的版本回退见 §9，不是这里说的代码回退。）

### P0-2 分组与书签：同类静默失败 + 非原子写

**位置**：`GroupStore.kt:18-38`（`load` catch 返回空列表）、`:40-49`（`save` 用 `writeText`）、`BookmarkStore.kt:87-116`（`readAll` catch 返回空 map）、`:118-145`（`writeAll` 全量覆盖且 `runCatching{}` 吞异常）

**影响**：
- 分组文件写一半被杀进程会留下半截 JSON；配合 `load()` 静默空列表，下一次 `add/rename/remove`（`:52-98`）就把空列表写回 → 分组全丢。
- `BookmarkStore` 用 `AtomicFile` 但 `readAll()` 失败返回空 map，随后 `add()`（`:43-64`）会 `writeAll` 只含这一条新书签的内容 → **一次添加书签抹掉全书全部书签**。

**方案**：三处 `load/readAll` 统一采用 P0-1 的三态 + 「失败不写」；`GroupStore.save` 改 `AtomicFile.startWrite/finishWrite/failWrite`；`BookmarkStore.writeAll` 去掉外层静默 `runCatching`，让失败显式上报（否则调用方以为写入成功）。`GroupStore` 的备份按 P0-1 同款策略，书签按「可丢」定位保留损坏现场即可。

### P0-3 删除分组：两步之间失败会留下不一致

**位置**：`GroupStore.kt:93-98`、`MainActivity.kt:848-852`

**证据**：`GroupStore.remove()` 先 `save(context, list)` 删掉分组文件里的组，再 `BookStore.clearGroup(context, id)` 清书籍归属。若第二步失败或进程在两步之间被杀，结果是「分组已删、书的 `groupId` 仍指向不存在的组」。UI 层「同时从书架删除」还会先逐本 `BookStore.remove()`（每本一次全量 load+save），再 `GroupStore.remove()`，写窗口更多。跨文件没有原子事务，`try/catch` 补偿挡不住进程被杀。

**方案**（让失败落在可自愈的方向，而不是靠补偿）：
1. **调换顺序**：先 `BookStore.clearGroup()` 清书籍归属，再删分组。这样中断后最多是「书已无归属、分组还在」——一个空分组，无害且可再删；反过来则会留下悬空 `groupId`。
2. **加载时自愈，但必须以分组读取成功为前提**：
   - 先取 `GroupStore.load()` 的三态结果；**只有 `Ok` 才允许清理悬空 `groupId`**。
   - `Failed`（读盘 / 解析失败）时**保留书籍归属原样，不清理、不写盘**——否则一次读盘异常就会把正常分组关系当成「悬空」全部清空，损失比悬空 id 更大。
   - `Missing`（分组文件不存在）是合法空库；但若此时书里仍有非空 `groupId`，说明分组文件可能意外丢失，按保守策略同样跳过自愈并上报，不清空。
   - 清理结果不在自愈当下单独写盘；等分组读取恢复 `Ok` 后，在**下一次合法写入**时把清理结果一并落盘（幂等，可重复执行）。
   - 这条同时兜住历史遗留数据，并防止 P0-2 修复前 `GroupStore.load()` 静默返回空列表（`:35-37`）导致归属被整体清空。
3. UI 的「同时从书架删除」收敛为一次批量提交，不要循环逐本 `remove()`（每本都全量读写整库）；`GroupStore.remove` 与 `BookStore.clearGroup` 放在同一个 `@Synchronized` 边界内。

**验证**：注入「清归属成功、删分组失败」和「两步之间进程被杀」两种失败 → 断言不出现悬空 `groupId`；注入「分组读取失败」→ 断言书的 `groupId` 保持原值、书库文件未被改写，待分组恢复 `Ok` 后下一次写入才落盘清理结果；自愈逻辑幂等。模拟器跑相关几条。

---

## 4. P1 · 用户可感知的性能（先测量，再动手）

### P1-1 多版本分页缓存：验收目标「切回用过的排版命中」

**位置**：`PaginationCache.kt:13`（固定 `book-$bookId.bin`）、`:15-19`、`:21-27`、`:48-71`；调用点 `Paginator.kt:52-64`、`PageView.kt:186,289-321`

**证据链**：`onSizeChanged` 和 `setFontSize` 等都会 `paginate()`（`PageView.kt:287-294`）；尺寸/字号/字体任一变化，key 就变，唯一缓存文件不命中 → 全本重算 → 写缓存时**覆盖**原文件。改回原字号也要再等一次 10~15 s。

**验收目标（明确）**：**切回用过的排版时命中缓存**。**第一次**换到新字号或新尺寸仍然要重算，这是预期行为，不要用「先拿旧 ranges 顶着渲染」去掩盖它 —— v1 提过的这个可选做法已移除。

**方案**：
1. 缓存文件名改为 `book-<id>-<keyHash>.bin`，按 LRU 保留最近 3~4 份（总容量上限如 32 MB）；`read()` 按 key 直接定位，不再固定单文件；保留现有 legacy 迁移逻辑（`:23-26`）。
2. `key()` 含 `document.text.hashCode()` 与 `Build.FINGERPRINT`，文件名必须继续走 `SAFE_ID` / SHA-256 散列（`:79-83`），不要直接拼 bookId。

**实现备注（W2 落地时处理，不单列验收项）**：若按文件修改时间淘汰，**命中时必须更新访问时间**，否则「一直用但很久没重算」的常用排版会因为 mtime 最老而被淘汰，与「切回用过的排版命中」直接冲突。`File.setLastModified()` 在部分设备上精度有限，建议用单独的元数据记录 `lastAccess`，或直接改成按访问时间戳淘汰。

**前置测量（W1 内完成，作为 W2 是否值得做的门禁）**：用《佛本是道》分别量「打开到首屏可见」和「首次分页完成」两段耗时，各跑 3 次取中位数，注明冷/热启动与设备。只有确认首次分页确实是大头，多版本缓存的收益才成立；若瓶颈在打开到首屏（TXT 解码/文档构建），优先顺序要相应调整。

**验证**（分页/缓存级别，必跑）：
- `ReaderRegressionTest.kt:440` `collidingBookIdsUseSeparateCacheFilesAndMigrateLegacyRanges` 必须继续通过（测的正是缓存文件名冲突）。
- 新增用例：同一本书生成 3 个不同 key 的缓存 → 断言三份共存且各自命中；超过上限后最旧被清。
- 实测「A 尺寸 → B 尺寸 → 回 A 尺寸」三段耗时：**B 段允许慢（首次重算），A 段必须命中缓存**。

**代码回退**：缓存可丢弃（`PaginationCache.kt:10`），清空 `cacheDir/reader-pagination` 即恢复旧行为。

### P1-2 TXT 解码：测量后决策，采样必须全文校验或回退

**位置**：`TextCodec.kt:23-52,54-70,72-83`；`Document.kt:43-74`

**现状问题（保留描述，暂不排期实施）**：`decode()` 整文件 `readBytes()` 再生成全本 UTF-16 字符串；`detect()` 对整本书做严格 UTF-8 解码、截断重试，非 UTF-8 书还会对 GB18030 / Big5 各解一遍并逐字符算中文占比（最多 4 份全本字符串）；`Document.loadTxt()` 再 `replace` ×2 + `trim()` + `split('\n')` 物化全部行 + `StringBuilder.toString()` + 再次 `trim()`。

**决策门禁**：**先量「打开到首屏」和「首次分页」分别耗时**，再判断 TXT 解码是否值得提前或值得改。如果「打开到首屏」占比很小，这项就降级或不做；不要凭静态推断提前优化。

**采样方案的正确性约束（v1 有漏洞）**：只读前 256 KB 采样会把**长 ASCII 开头、后面才出现中文**的书判错（英文序言 / 版权页 / 编码切换的正文），也会让 GB18030、Big5 在纯 ASCII 采样段上无法区分。因此采样**不能无条件生效**：
1. BOM 优先，逻辑不变。
2. 采样给出候选 charset 后，必须对**全文**做校验：用流式 `CharsetDecoder` 分块解码（不物化整本 String），统计非法序列 / 替换字符 / 中文占比；与采样结论不符就**回退到现有全量候选打分**（`TextCodec.kt:40-50` 的原路径）。
3. 只有全文校验通过的采样结果才允许采用；校验失败不得猜一个 charset 硬解。
4. 保留 `trimIncompleteUtf8`（`:85-110`）处理尾部截断的语义，别在快路径里丢掉。

**若实施，还必须保住章节偏移**：`Document.kt:67-73` 的 `shift` 补偿是历史踩坑（trim 吃掉文首缩进导致断页落进标题），`ReaderRegressionTest.kt:206 chapterOffsetsPointAtHeadingText` 和 `:177 everyChapterStartsOnItsOwnPage` 必须原样通过。

**验证**：样本集必须包含「UTF-8 / GB18030 / Big5」×「中文开头 / 长 ASCII 开头后接中文」至少 6 组，断言判定结果与原实现一致；JVM 单测做字符集对照。改动前后「打开到首屏」耗时与 `dumpsys meminfo` PSS 峰值各跑 3 次取中位数。

### P1-3 全文搜索后台化（章节二分随附）

**位置**：`ReaderActivity.kt:1480-1558`（`runSearch`）、`:1271-1284`（`indexOfFolded`）、`:1561-1565`（UI 线程直接调用）、`Document.kt:21-30`（`chapterIndexAt` 线性）、`PageView.kt:470-471`（分页单线程执行器）

**证据链**：`searchButton.setOnClickListener { runSearch(...) }` 无任何后台包裹；循环内 500 次 `doc.chapterAt(at)`（每次从 0 线性遍历 chapters）、100 次 `sentenceAround` + `highlightKeyword`（`SpannableString`）、200 个 `TextView` 一次性 `addView`。`indexOfFolded` 对纯 ASCII 查询是逐字符 `while`，英文长词需扫全本。

**方案**（主项是后台化，章节二分随搜索一起做，不单独提前）：
1. `runSearch` 拆成「后台算 `matches` + 句子」→「主线程渲染」：**新建独立的搜索 `ExecutorService`，不要复用 `PageView` 的单线程 `layoutWorker`**（`PageView.kt:470`）。用类似 `layoutGeneration`（`PageView.kt:304-328`）的代次号丢弃过期结果，再点一次搜索即取消旧任务。
2. 结果列表改 `RecyclerView` 或按滚动懒加载，避免一次建 200 个 View；`displayCap=100` / `found<500` 截断保留，但把「共 N 处」改成真实总数或明确标注「前 500 处」，避免用户误以为全书只有这些命中（项目日志已注意到「的」会截断）。
3. **随附**：`Document.chapterIndexAt` 改二分（`Paginator.kt:124` 已有 `binarySearchBy` 写法），`chapterAt` 复用它。这同时改善翻页 `PageView.notifyChanged()`（`:278`）与搜索，由 `ReaderRegressionTest.kt:283 chapterIndexAtFindsCurrentChapter` 守护。

**实现备注（W3 落地时处理，不单列验收项）**：搜索执行器必须独立于分页执行器——`layoutWorker` 是单线程，大书正在后台排版时搜索请求会排在同一队列后面，等于没解决卡顿。代次号 / 取消机制可以沿用 `layoutGeneration` 的思路，但两者各自独立，不要共享队列。

**注意**：搜索态「点结果 → 上一个/下一个」被 `DeviceSmokeTest` 与人工实拍验证过，改异步时必须保证 `enterSearchMode(query, matches, index)` 拿到的 `matches` 与旧逻辑完全一致（含大小写折叠规则）。

**验证**：模拟器实点搜索「的」与一个英文词各一次 + 连点「下一个」3 次；搜索期间能正常滚动/返回、无白屏卡顿；`DeviceSmokeTest.kt:191` 目录用例不回归。

### P1-4 玻璃路径：先测掉帧，再决定改哪条

**位置**：`GlassBar.kt:93-123`、`:130-142`；`GlassCardView.kt:93-124`、`:134`；`Blur.kt:8-19`；`LiquidGlass.kt:38-41`；`BookCoverView.kt:248-257`

**已知代码事实**：`OnPreDrawListener` 在 dirty 且距上次 >120 ms 时调 `captureFrom()`，把整页 `view.draw(c)` 重绘到 0.28× 的 `ARGB_8888` 位图，再 `Blur.blurred(..., scale=1f)` 做三轮盒式模糊（每轮分配 `IntArray`）；`dispatchDraw` 里 `LiquidGlass.create()` 每帧 `new RuntimeShader`（`:142`），同时每次新建 `Path` / `RectF` / `IntArray(2)` / `Matrix`；`BookCoverView.drawGirl` 每次 `onDraw` 新建约 7 个 `Paint` / `Path`；而 `AgslGlassView.kt:217-224` 已走 GPU `RenderEffect`。

**门禁**：**先量化书架滚动与设置面板的掉帧**（`dumpsys gfxinfo` / Perfetto / `Choreographer.postFrameCallback`，各跑 3 次取中位数），定位是「整页 View 重绘 + CPU 模糊」还是「GPU 折射 shader」占大头，再决定改哪条路径。**不要预先认定** `RenderEffect` 替换就是答案。

**候选路径（测量后再选，可只做其中一条）**：
- 零行为变化项：`RuntimeShader` / `Path` / `RectF` / `IntArray` / `Matrix` / 封面 `Paint` 提为复用字段，尺寸变化时只 `LiquidGlass.configure()`。
- 若瓶颈在 CPU 模糊：API 31+ 用 `RenderEffect.createBlurEffect` 与 `AgslGlassView` 统一；API <31 保留软件模糊但改成滚动停止后刷新、复用 `Bitmap`。
- 若瓶颈在 GPU 折射：优先调 AGSL shader 与录制区域，不动 `GlassBar`。

**验证**：按 §7，字段复用属资源类改动 → 编译 + 截图；换 `RenderEffect` 属 UI 接线 → 模拟器点一遍书架滚动/设置面板/阅读底栏 + 平板截图核对（**平板只截图，不跑测试**）。验收用同一套掉帧基线做前后对比，不凭主观「感觉跟手」定性。

### P1-5 书架刷新：先测掉帧；若采用 DiffUtil 必须提交不可变快照

**位置**：`BookshelfAdapter.kt:38,44,53,60,67,74,83`（7 处 `notifyDataSetChanged()`）、`SearchActivity.kt:236`

**门禁**：与 P1-4 共用一次掉帧测量，确认全量刷新确实是瓶颈再改；否则保持现状。

**关键陷阱**：`Book` 是**字段可变**的 data class，`BookshelfAdapter` 持有的是同一批对象引用。若新旧列表共用同一批可变 `Book`，`DiffUtil.ItemCallback.areContentsTheSame` 比较时旧项已经被就地改过（喜爱/置顶字段已变），会判定「内容相同」而**漏掉刷新**。

**若采用 DiffUtil**：`submit()` 前必须为每本书生成**不可变快照**（`book.copy()`，或把 `Book` 字段改为 `val`、所有状态变更走 `copy()` 后整体替换列表），保证新旧列表不共享可变对象；`areContentsTheSame` 覆盖 `favorite/pinned/groupId/progress/coverPath/defaultCoverIndex` 等所有影响显示的字段；`areItemsTheSame` 只按 `id`。

**验证**：新增用例「先持有旧列表 → 就地改 `favorite` → 提交新快照 → DiffUtil 必须报内容变化」；模拟器点一遍置顶、喜爱、长按多选、拖选、分组，观察错位点选。

---

## 5. P2 · 工程效率与长期项

### P2-1 渐进拆分，不做架构重写
两个 Activity 合计 3499 行，`MainActivity` 有 7 处裸 `Thread` + `runOnUiThread`，`PageView.kt:470-471` 的 `companion object` 持有永不关闭的静态线程池。建议**只抽纯逻辑**：把 `indexOfFolded/sentenceAround`、`chapterIndexAt`、`TextCodec.detect`、分页 key 生成、三份 JSON 的读写抽成不依赖 Android View 的对象，先让它们能被 JVM 单测覆盖；再统一一个 `AppExecutors(io, main)` 并跟 Activity lifecycle 做取消。现阶段引入 ViewModel/Hilt/协程全家桶属于过度设计，明确**不做**。

### P2-2 补 JVM 单测与固定性能基准
现有 30 个 instrumented 测试必须开模拟器/真机，反馈慢。新增 `app/src/test/`：编码检测对照表（含长 ASCII 开头样本）、`chapterIndexAt` 边界、`PageBreaker` 小样本断页、三份 JSON「坏文件不覆盖」、备份结构校验与正文缺失过滤、分组自愈前置条件、`PaginationCache` 多版本 LRU。固定一条大书基准（样本 `D:/历史数据/小说/网文/佛本是道.txt`），**分开采集「打开到首屏可见」与「首次分页完成」**，外加缓存命中分页耗时、PSS 峰值、书架滚动掉帧数；各跑 3 次取中位数，注明冷/热启动与设备（模拟器 `ithome-api36` / 平板 OPD2409，2400×3392 @420dpi）。

### P2-3 构建与包体
`app/build.gradle.kts:19-21` release 关闭了压缩。建议开 `isMinifyEnabled = true` + `isShrinkResources = true`，先跑一次 `assembleRelease` 对比 15 MB 基线，并核对第三方 `liquidglass` 与 32 位 so（发版清单里「保留 armeabi-v7a」是已确认取舍，别顺手删）。R8 开启后必须重跑全量 instrumented 测试与平板冒烟，因为反射/资源名可能被改。补 `lint.xml` 与最小 CI（编译 + JVM 单测）即可。

### P2-4 权限收敛（明确不做配对码）
`MANAGE_EXTERNAL_STORAGE` 自用无碍，但上架应用商店会被拒；导入已走 `ContentResolver.openInputStream` + SAF（`BookStore.kt:228-229`），可评估去掉。`SearchActivity` 无 intent-filter 却 `exported=true`，可设 `false`。`allowBackup=true` 会把本地书库纳入备份，若在意隐私可显式关掉或配 `dataExtractionRules`。

**不做**：局域网传书配对码。既定交互就是同一可信网络内直接输入地址，加配对码属于改变产品决策，不列入本次计划。现有 Origin + 自定义头 + 文件名/扩展名/大小白名单 + 绑定具体 WiFi 接口 IP 维持现状，README「已知限制」继续如实说明明文 HTTP、无鉴权即可。

---

## 6. 落地顺序（Wave）

| Wave | 内容 | 依赖 | 完成后 |
|---|---|---|---|
| W1 | P0-1 三态 + 失败不写 + 独立校验备份（**含结构校验、正文存在性校验**）；P0-2 分组/书签；P0-3 删除分组顺序与**带前置条件的自愈**；**同时量出「打开到首屏」与「首次分页」两段耗时** | 无 | 数据不再丢；恢复后不出现打不开的书；有性能基线可决策 |
| W2 | P1-1 多版本分页缓存（落地时处理「命中刷新访问时间」） | W1 的测量结论 | 切回用过的排版命中缓存 |
| W3 | P1-3 搜索后台化 + 章节二分（落地时用**独立执行器**） | W1、W2 稳定 | 大书搜索不卡、不与排版排队 |
| W4 | 按掉帧基线决定 P1-4 / P1-5 改哪条；按 W1 测量结论决定 P1-2 是否实施 | W1 的掉帧与耗时基线 | 只做有数据支撑的那条 |
| W5 | P2-2 单测 → P2-3 R8 → P2-4 权限 | 前面都验证过 | 发版前全量一次 |

每 Wave 之间留一个 checkpoint（改动文件清单 + 验证证据 + 未完成项）。**按项目约定攒批发版**：`versionName` 第三位两位递增、`versionCode +1`，同步 README 徽章，走 `docs/RELEASE-CHECKLIST.md`；**`dist/` 只放发版包**。

---

## 7. 验证路径（沿用项目既有约定，不擅自加码）

| 改动类型 | 验证方式 |
|---|---|
| 颜色/文案/尺寸/资源 | 只编译 + 截图 |
| UI 接线、交互（搜索异步、DiffUtil、RenderEffect 切换） | 编译 + 模拟器点一遍 |
| 分页/缓存/导入/数据格式（W1/W2 大部分） | 跑相关的那几条：`adb -s emulator-5554 shell am instrument -e class <类>#<方法>` |
| 发版前 | 全量一次 |

固定约束：
- **日常测试直接用 Gradle 的 debug 输出**（`app/build/outputs/apk/debug/app-debug.apk`）；`dist/` 只留发版包，不要拿它当日常构建目录。
- 大书验证固定用《佛本是道》（3.8 MB / 2112 页），导入走 `adb push` + `run-as` 直落应用目录，绕开 SAF。
- **平板 OPD2409（be6948fc）只允许 `install -r` 和截图**，绝不 `uninstall`、绝不跑 `connectedDebugAndroidTest`（会清书库）；所有 instrumented 测试只在模拟器跑。
- 装包一律 `install -r`，不要卸载。注意 `install -r` 用于**同或更高** versionCode 的日常升级；向低 versionCode 回退会 `INSTALL_FAILED_VERSION_DOWNGRADE`（`-d` 只对 debuggable 包有效，release 包不适用）。详见 §9 回滚。
- 模拟器验证完保持后台运行别关；**发版完成后**再关。

---

## 8. 度量基线与验收判据

**已有基线**（来自项目日志与构建产物，非本轮实测）：

| 指标 | 当前值 |
|---|---|
| 《佛本是道》首次分页 | 10~15 s，2112 页（**未与「打开到首屏」分开测**） |
| 搜索 | 上限 500 命中 / 渲染 100 条；连点「下一个」计数正常 |
| release APK | 15 MB |
| instrumented 测试 | 30 条（0.2.21 发版时 30/30 通过） |
| JVM 单测 | 0 |
| 书架滚动掉帧 | **未采集** |

**W1 必须先采**（否则 W2/W4 无法证明收益）：「打开到首屏可见」耗时、「首次分页完成」耗时、缓存命中分页耗时、PSS 峰值、书架滚动掉帧数。每项跑 3 次取中位数，注明冷/热启动与设备。

**逐项验收判据（可执行）**：
1. P0-1：坏 JSON + 分别调置顶 / 分组 / 导入 → 三处文件内容均未被改写、损坏副本存在；从**独立校验备份**恢复后每条书的正文都存在且内容可解析；**备份后批量删书再恢复 → 已删书不重新出现在书架、保留书都能打开、恢复报告列出被剔除条目**；条目数合法骤降不得被误判为备份损坏；备份结构不合法时不得采纳、主文件保持只读。
2. P0-2：`groups.json` / `bookmarks.json` 写盘异常注入后原文件仍可解析；坏文件下一次添加书签不得只写一条新书签。
3. P0-3：注入「清归属/删分组」两步之间的失败 → 无悬空 `groupId`；**注入分组读取失败 → 书的 `groupId` 保持原值、书库文件未被改写，分组恢复 `Ok` 后下一次写入才落盘清理结果**；自愈逻辑幂等。
4. P1-1：A 尺寸 → B 尺寸 → 回 A 尺寸，**B 段允许重算、A 段必须命中**；`ReaderRegressionTest.kt:440` 通过；超容量后最旧缓存被清（访问时间刷新按 W2 实现备注处理）。
5. P1-2（若实施）：样本集含「长 ASCII 开头、后接中文」的 UTF-8 / GB18030 / Big5，判定与原实现一致；采样结论未被全文校验通过时必须回退；`chapterOffsetsPointAtHeadingText` 通过。
6. P1-3：搜索期间 UI 可响应；`matches` 与旧逻辑逐条一致；连续「下一个」不跳号；章节二分用例通过；大书排版进行中发起搜索不应排队等待（按 W3 实现备注验证）。
7. P1-4 / P1-5：**先有掉帧基线**再做前后对比；若用 DiffUtil，「就地改 `favorite` 后提交新快照」必须被判为内容变化。
8. P2-3：APK 体积对比 15 MB；全量 30 条 instrumented 通过。

---

## 9. 已知限制、未验证项与风险

- 本轮是**静态走读**：未编译、未跑 instrumented 测试、未跑 profiler、未装包。除「代码路径确实存在」外，关于耗时/内存/掉帧的结论均标为**预期收益或疑似**，须按 §8 实测。
- P0-1 / P0-3 是**条件触发**（需坏文件或两步之间失败），不是必现故障；优先级基于后果严重度而非复现概率。
- **`AtomicFile` 语义更正**：`openRead()` 只处理遗留 `.bak`；`startWrite()/finishWrite()` 用 `.new` 原子替换主文件，成功写入后不留旧版本。**恢复能力必须靠另行维护、且经过校验的备份**，不能依赖 `.bak`（依据 AndroidX `core/util/AtomicFile.java`）。
- **备份是某一时刻的书库快照，可能与磁盘不一致**：`BookStore.remove():305-309` 先写书库再删正文/封面，删除前生成的备份可能含已删书。所以备份合法性不能只看 JSON 完整，必须做结构校验 + 恢复时逐条校验正文存在性；条目数骤降只是诊断信号，批量删书是合法操作，不能当损坏判据。
- **分组自愈是保守清理，不是严格修复**：只有在分组读取 `Ok` 时才执行；`Failed` / 意外 `Missing` 时保留归属，避免一次读盘异常清空正常分组关系。跨文件（书库 / 分组 / 书签）没有原子事务，P0-3 不声称能做到严格事务一致。
- P1-4 的 CPU 模糊开销未量化；`Blur.blurred` 调用点传 `scale=1f`，但 `captureFrom` 已把位图缩到 0.28×（`GlassBar.kt:42,97-98`），主要开销是「整页 View 重绘 + 三轮盒式模糊」，不是全屏像素模糊，不要按最坏情况估算。
- P2-3 的 R8 收益未知，且可能引入反射/资源名问题，必须放在 W5 并配全量回归。
- 平板现有 0.2.21 与 GitHub Release 曾出现「同号不同包」的历史问题（项目日志 2026-11-12）；任何发版都按发版清单核对版本号与 md5。
- **回滚**（v2 修正）：代码层可按 Wave 单文件回退，缓存可丢弃；但**发版后的回退不能靠 `install -r` 装回低 versionCode 的旧包**（会 `INSTALL_FAILED_VERSION_DOWNGRADE`，`-d` 也不适用于 release 包）。正确做法是**用旧代码构建一个更高 `versionCode`、同一签名（`C:/Android/.android/debug.keystore`）的修复包**，按发版清单走版本号、签名、`install -r` 与 release 流程。日常验证用 Gradle debug 输出，`dist/` 只留发版包。

---

## 10. 建议的下一步

从 **W1** 开始：一次性把书库三态 + 失败不写 + 独立校验备份（结构校验、正文存在性校验）、分组/书签同类修复、删除分组顺序与带前置条件的自愈做完；验收跑「备份后批量删书再恢复」和「分组读取失败保留归属」两条新用例，同时量出「打开到首屏」和「首次分页」两段耗时。W1 完成后按 §7 只跑相关几条 instrumented 测试，不必全量。

有了这两段耗时和书架掉帧基线，再决定 **W2 是否按原顺序做多版本缓存**，以及 **W4 里 TXT 解码、玻璃、书架刷新三条到底动哪一条**。分页缓存访问时间、搜索独立执行器两条留到 W2 / W3 落地时处理。配对码不列入计划。
