# Markdown 递归切块改造说明

日期：2026-10-21。基线提交：`d95624c`。

已在现有 `CharacterChunker` 中接入真正的递归文本切分，保留类名和 `Chunker` 接口。Spring 中仍只有这一个默认 Chunker Bean。配置仍为目标 600、硬上限 1000、正文 overlap 0，长度统一按 Unicode 码点计算。

本次完成主代码编译、静态正确性核对和三份现有文档的离线分块导出。按照当前 `AGENTS.MD` 和用户直接给出的约束，没有编写测试用例、修改测试类或执行测试。离线导出只使用仓库已有文档，没有构造合成输入或访问模型、数据库、ES、MQ、对象存储。

## 1. 文件清单

| 文件 | 变更及职责 |
|---|---|
| `rag/src/main/java/com/llll/rag/ingestion/chunker/CharacterChunker.java` | 修改；复用标题栈和结构处理，接入递归正文切分、分组拼装、超长代码行拆分和标题预算降级 |
| `rag/src/main/java/com/llll/rag/ingestion/chunker/RecursiveTextSplitter.java` | 新增；普通正文按分隔符优先级递归拆分，保留分隔符并合并短片段 |
| `rag/src/main/java/com/llll/rag/ingestion/chunker/HtmlTableSplitter.java` | 新增；超长 HTML 表格的完整行组拆分、重复表头、跨行约束校验和错误日志 |
| `rag/src/main/java/com/llll/rag/ingestion/chunker/ChunkBudgetException.java` | 新增；区分可通过减少标题上下文重试的预算不足与不可降级的结构错误 |
| `rag/src/main/java/com/llll/rag/ingestion/model/DocumentBlockType.java` | 追加 `THEMATIC_BREAK`，只表达文档结构，不涉及数据库结构 |
| `rag/src/main/java/com/llll/rag/ingestion/parser/MarkdownDocumentParser.java` | 小范围修改；保留顶层 Markdown 分隔线作为切块边界，清理失效的历史实现注释 |
| `rag/docs/Markdown递归切块改造说明.md` | 新增；交付说明、真实文档对照和验证边界 |

原有 Parser、Chunker 两个文件末尾失效的历史大段注释，按任务书要求清理。本次被替换的字符窗口算法按代码风格约束保留为 `/** ... */` 审阅对照，不再执行。

没有修改 Embedding、PG 表结构、ES 索引结构、MQ 消费流程、状态机、RRF、Rerank 或 Agent 工具。没有增加依赖：当前依赖树确认已有 Tika → tika-parser-code-module → jsoup 1.23.2，可直接复用。

## 2. 现有调用关系

```text
DocChunkConsumer
  → DocumentParserRouter.require(fileType)
  → MarkdownDocumentParser / MinerUDocumentParser
  → ParseResult.blocks
  → CharacterChunker.chunk(blocks)
  → List<DocumentChunk>
  → EmbeddingService.embedBatch(chunk.content)
  → KnowledgeChunkServiceImpl.saveChunks
  → saveChunksInEs
```

MinerU 的结果继续复用 Markdown Parser。`DocumentChunk.content` 仍同时作为向量化输入和入库正文，没有引入另一套 embedding 文本格式。必要标题和表头可重复，正文不配置 overlap。

## 3. 算法流程

1. 使用 CommonMark 已有 AST 识别的一至六级全局标题维护标题栈。同级或更高级标题替换旧路径，只有出现正文才创建章节，因此连续标题和仅标题输入不会产生标题孤块；仅标题文档明确报错。
2. 顶层 Markdown 分隔线结束当前正文组。分隔线本身不作为检索正文；边界组编号阻止最终短章节合并跨过这条线。容器内的装饰线不提升为全局边界。
3. 先扣除标题和两次换行的码点预算。章节正文整体能放下时保持完整，即使超过目标 600，只要不超过最终硬上限也无需细分。
4. 对超长章节，将相邻普通段落按 Parser 的规范化段落分隔符组合。只有超限片段进入递归，优先级为：段落 → 换行 → 中文句末 → 英文句末 → 中文分句标点 → 带空白上下文的英文分句标点 → 空格/制表符 → Unicode 码点。
5. 每层只选择当前优先级的边界；短片段进入合并，仍超限的片段才递归到下一层。英文句末需要相邻空白或结束引号等上下文，不将小数、版本号和域名中的点直接作为句末。
6. 普通正文切片通过相接的 `substring` 保留原分隔符，不 trim 或重新 split 丢弃字符。合并以接近目标长度为软约束，不为了达到 600 再拆开能完整放下的段落。
7. 结构分组保留递归产生的断点；同一正文的两个续片不再用额外空行拼接。不同 AST 子块之间沿用原规范化分隔符。
8. 最终装配包含标题、列表缩进、引用标记、代码围栏及表头的完整文本，再检查是否为空或超过硬上限，使用最终结果数量生成从 0 连续递增的 index。

原有同父路径、三级及以下完整短章节合并规则继续保留，但不能跨越新增的分隔线边界组。

递归和合并的职责划分参考 [LangChain RecursiveCharacterTextSplitter 源码](https://github.com/langchain-ai/langchain/blob/master/libs/text-splitters/langchain_text_splitters/character.py)；标题与结构边界复用本项目 AST，没有照搬对原始 Markdown 做标题正则切分的方式。[ChunkViz](https://chunkviz.up.railway.app/#explanation) 页面需要 JavaScript，不能将其静态页面读取当作完整实现审计。

## 4. 结构及预算规则

| 内容 | 当前处理 |
|---|---|
| 标题 | 默认保留路径；预算不足先去掉最远祖先。最具体标题仍超长时保留 Unicode 安全前缀与省略号并记录日志；最小保留一个原始码点。仍无法容纳结构时明确失败 |
| Markdown 表格 | 小表整体保留；长表按完整数据行分配，每片重复规范化表头。单行连同表头放不下时明确失败，不切单元格 |
| HTML 表格 | 小表原样保留；超长时仅处理独立、结构可识别的表格。要求明确的 `thead` 或起始全 `th` 表头，不将第一条普通数据行猜成表头 |
| rowspan | 将直接及传递关联的完整多行作为不可拆行组；`rowspan=0` 覆盖所属行组剩余全部行。跨出表头/行组的值明确报错 |
| colspan | 保留完整单元格与原跨度属性，要求合法正整数；不按列切分，也不重排单元格 |
| 代码块 | 小块完整保留；长块优先按原代码行拆分；只有超长单行才按码点细分，各片重新封装足够长的合法围栏 |
| 列表/引用 | 优先保留完整项；单项超长时才拆内部正文，各片重建原项目符、缩进或引用符，并计算全部结构预算 |

HTML 结构错误不能通过去标题后无条件降级为普通文本。预算不足才允许去掉祖先标题后重试；最终失败会抛出带原因的异常。HTML 辅助类另记录 Block 序号和失败原因。

## 5. 与改造前的差异

| 项目 | 改造前 | 改造后 |
|---|---|---|
| 超长普通文本 | 在字符窗口内寻找距离目标较近的断点 | 选择高优先级分隔符，仅对超限片段逐层递归，分层合并 |
| 超长章节合并 | 结构片段主要按硬上限装箱 | 合并同时考虑目标大小，保留同一子块内部的已有断点 |
| HTML 表格超限 | 统一按 RAW 结构报错 | 明确表头和安全行组可拆分；不能安全处理时仍明确失败 |
| 代码单行超限 | 直接报错 | Unicode 安全细分后重新封装围栏 |
| 最具体标题超限 | 去除祖先后仍然报错 | 明确缩略标题前缀并记录，正文不截断 |
| Markdown 分隔线 | Parser 丢弃，不能阻止后续合并 | 保留为结构边界，但不生成装饰线孤块 |
| 标题栈、Markdown 表格完整行、零 overlap、输出模型 | 已有实现 | 继续复用 |

## 6. 真实文档前后对照

使用基线提交的原 Parser、原 CharacterChunker，与当前版本分别离线处理三份已有文档，使用相同默认配置。旧生产源码快照和离线导出工具只放在 `tmp/chunking-review-20261021`，没有放入主代码或测试目录。

| 实际文档 | 改造前 Chunk 数 | 改造后 Chunk 数 | 改造前最大码点数 | 改造后最大码点数 |
|---|---:|---:|---:|---:|
| `tmp/apple-corpus-20261020/sources/02_Numbers表格整理与格式交换.md` | 8 | 8 | 499 | 499 |
| `docs/商城助手评测方案-v1.md` | 17 | 17 | 979 | 979 |
| `rag/src/main/java/com/llll/rag/knowledge/storage/README.md` | 5 | 5 | 699 | 699 |

每版共 30 块，最短 104、最长 979 码点。第一、三份文档的分块内容保持一致；第二份文档末尾发生边界变化，其他块保持一致。Chunk 数相同不代表所有断点相同。

第二份文档的实际差异：

```text
改造前 Chunk 15：752 码点
  ……项目源码列表最后一项
  外部指标定义：

改造前 Chunk 16：475 码点
  商城助手评测方案 v1 / 11. 依据
  Ragas Context Recall ……
  Ragas Faithfulness ……
  Microsoft RAG evaluators ……

改造后 Chunk 15：743 码点
  ……项目源码列表最后一项

改造后 Chunk 16：484 码点
  商城助手评测方案 v1 / 11. 依据
  外部指标定义：
  Ragas Context Recall ……
  Ragas Faithfulness ……
  Microsoft RAG evaluators ……
```

这里的“外部指标定义：”是普通段落，移动来自新的合并预算，不应推广成对任意引导段落都具有强制绑定规则。标题绑定由独立的 AST 标题路径机制负责。

完整逐块正文和长度在：

- [统计表](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/summary.tsv)
- Numbers 指南：[改造前](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-1-before.md)、[改造后](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-1-after.md)
- 评测方案：[改造前](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-2-before.md)、[改造后](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-2-after.md)
- 存储说明：[改造前](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-3-before.md)、[改造后](D:/Program/AgenticRag/tmp/chunking-review-20261021/output/document-3-after.md)

这些是实际离线导出结果，不代表真实知识库已经采用新边界，也不代表完整场景覆盖。

## 7. 已执行的核对

- 实际阅读了任务书指定的 Parser、Normalizer、MinerU Parser、模型、计数器、配置、消费端、保存服务和两个现存测试文件。
- 确认当前配置为 600/1000/0、唯一 Chunker Bean、向量化与保存调用关系及现有 jsoup 依赖来源。
- Java 21 主代码编译通过，包含根项目、framework、rag、agent、bootstrap 五个 Maven 模块：

```powershell
$env:JAVA_HOME = 'C:\Users\Lijianwen\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B -ntp -pl bootstrap -am '-Dmaven.test.skip=true' '-Dmaven.compiler.proc=full' compile
```

- 三份现有真实文档的新旧分块导出完成，输出逐块内容、index 和 Unicode 长度。
- 静态核对了递归层级推进、码点切分索引、标点保留、标题预算、完整表格行归属、跨行组边界、围栏长度以及最终连续编号。
- 没有执行单元测试、Parser 测试或外部服务集成验证。没有“全部测试通过”的结论。

编译日志：[chunking-compile.log](D:/Program/AgenticRag/tmp/chunking-compile.log)。

## 8. 尚存边界与验证限制

1. 超长 HTML 表格中，`caption`、`colgroup`、嵌套表格、表外混合正文、畸形标签、无法明确识别的表头以及越界 rowspan，目前明确失败并记录原因；小于预算的原表仍保持完整。这些情况没有被降级成字符切分。
2. 单个 Markdown 数据行、HTML 跨行组或必要表头即使减少标题后仍放不下，必须失败。实现不能同时保证不截断单元格和容纳任意超长单元格。
3. 超长代码中的纯空白续片，以及无法和有效文字在预算内共同保留的极端空白正文，可能明确失败；不会悄悄删除空白来伪装成功。
4. 切分保证 Unicode 码点安全，不提供组合字符簇、emoji ZWJ 序列完整性的额外保证。英文缩写只作保守上下文判断，不使用自然语言句法模型。
5. 当前三份真实文档没有覆盖超长代码单行、特殊 Unicode、长标题及复杂 HTML 表格。受禁止构造/运行测试的约束，这些路径只完成静态核对，不能声称得到运行覆盖。
6. 现有 `CharacterChunkerTest` 的四参数 `DocumentBlock` 构造以及 80 字符 overlap 假设已与当前主代码不一致；Parser 测试也仍按旧章节模型断言。本次未修改或运行测试，因此这些已有的测试源兼容问题仍然存在。主代码编译通过不等同于测试源码可编译。
7. 本次没有评估检索质量、embedding 成本或吞吐提升，也没有变更在线知识库数据。

## 9. 后续重建知识库注意事项

正式采用新边界时，需要由用户另行安排重新解析、分块、Embedding，以及 PG 和 ES 的一致替换。应先保存现有原文、配置、旧 Chunk 与评测基线，避免把旧边界的向量和新边界的正文混用。

重建会改变部分 Chunk 边界、编号对应内容及可能的 Chunk ID，现有基于旧 Chunk ID 的检索标注需要重新核对。PG 和 ES 的更新应沿用现有业务阶段安排，并在完成后检查文档状态、Chunk 数量、向量数量和索引正文的一致性。

本次没有修改或删除 PostgreSQL 中已有 Chunk，没有重建 ES 索引，没有触发重新向量化或全量处理。
