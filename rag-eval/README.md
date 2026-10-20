# RAG 检索评测

Java 执行真实检索，Python Ragas 0.4.3 负责候选问题生成和 ID 指标。独立 CLI 只加载数据源、检索器、Embedding、RRF、Rerank，不启动 Web、Agent 或 RocketMQ 消费者。不修改数据库，不改变现有业务接口。

最新扩充结果：基于桌面目录原文，四种模式各完成 148 道真实问题，其中各有 128 道可回答题参与质量评分、20 道不可回答题单列；共 592 条结果全部成功。最新报告在 results/desktop100/retrieval_report.md，数据集在 datasets/desktop100/retrieval_dataset.jsonl；下方 30 题运行是首轮历史记录。

按本仓库工作约束，不编写或运行单元测试。真实检索评测仅在用户授权后执行。本次用户已允许真实检索评测。

## 本次已交付的真实运行

2026-10-20 已导出实际 77 篇文档、607 个 Chunk；150 题目标生成 136 道候选，131 道通过结构与原文证据校验，仍全部待人工审核。商品比较缺 16 题，表格缺 3 题，原因保存在 datasets/full/generation_summary.json，未凑数。

先完成小规模流程（10 题目标实际有效 6 题），产物位于 results/smoke10；随后从完整候选集按题型抽取 30 题，完成四组共 120 条真实检索，PG/ES 前后快照核对通过，未发生检索异常或结果缺失。其中 25 题可回答，5 题不可回答；质量指标的分母为 25。另行采集了 30 次真实生产入口耗时。

最终报告为 results/retrieval_report.md，正式可编辑候选集为 datasets/full/retrieval_dataset.jsonl。此处的“正式文件”是文件用途，不代表标签已人工批准。Java 构建、Python 语法核对和依赖一致性核对完成，未编写或运行单元测试。复算已有结果时使用完整数据集路径：

```powershell
$env:PYTHONUTF8 = '1'
$py = './rag-eval/.venv/Scripts/python.exe'
& $py rag-eval/scripts/evaluate_retrieval.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl
& $py rag-eval/scripts/generate_report.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl
```

## 安装与构建

需要 Python 3.12、Java 21+、项目现有本机配置。所有命令从仓库根目录运行。

```powershell
./rag-eval/setup.ps1 -Python '你的Python可执行文件' -Maven '你的mvn.cmd'
$env:PYTHONUTF8 = '1'
$py = './rag-eval/.venv/Scripts/python.exe'
```

Python 依赖安装在 rag-eval/.venv，不影响 Maven。setup 使用 maven.test.skip=true，不编译测试源码、不运行测试；在 JDK 23+ 上通过 maven.compiler.proc=full 启用现有 Lombok。

Java 自动读取 bootstrap/src/main/resources/agenticrag.yaml 及默认 local profile 的 agenticrag-local.yaml。数据库、ES、Embedding、Rerank 使用同一套项目配置和环境变量；不复制密钥到评测配置。不将第三方启动日志或异常响应输出到报告。

生成模型默认读取本机 DeepSeek 配置和 agent.chat.model。可通过 RAG_EVAL_API_KEY、RAG_EVAL_BASE_URL、RAG_EVAL_MODEL 覆盖；模型地址使用 OpenAI 兼容 base URL。本机 YAML 读取器仅支持该目录的基础和 local 两份配置及普通环境占位符；其他 profile 或远程配置请明确提供上述环境变量。

## 先完成 10 题

运行期间暂停文档上传、编辑、启停、重新处理及删除，确保共享知识库不变化。

```powershell
& $py rag-eval/scripts/export_chunks.py
& $py rag-eval/scripts/generate_dataset.py --size 10 --chunk-limit 40 --concurrency 2
& $py rag-eval/scripts/validate_dataset.py --dataset rag-eval/datasets/retrieval_dataset.jsonl
& $py rag-eval/scripts/run_retrieval.py --limit 10 --concurrency 1 --production-latency
& $py rag-eval/scripts/evaluate_retrieval.py
& $py rag-eval/scripts/generate_report.py
```

如果生成不足 10 题，generation_summary.json 会记录数量和原因。run_retrieval 处理实际可用题目，不会伪造缺少的问题或结果。真实依赖失败时保留结果及失败状态，不生成假指标。不包含 Mock 数据或 Mock 测试。

导出按文档启用、未删除、success，Chunk 启用未删除，知识库未删除筛选；与现有 PG 检索条件一致，但额外保留缺少向量的可用 Chunk 用于核对。快照含文档与 Chunk 更新时间、向量指纹、模型名称；不导出向量。文档数统计有有效 Chunk 的文档，不包含空文档。所有 ID 保持字符串。

## 审核与扩充

retrieval_candidates.jsonl 保留全部候选及校验问题；retrieval_dataset.jsonl 初次生成只收录结构有效候选，仍全部为 pending，可直接人工编辑 JSONL。

人工核对 query、reference、gold_chunk_ids 及 evidence；quote 必须逐字属于来源 Chunk，doc_id/doc_name 必须一致。完成后将 review_status 改为 approved，并填写真实 reviewed_by 和 reviewed_at（ISO 时间）。无法确认的题保持 pending；错误题可改为 rejected。代码不会自动批准，也不会将 LLM 判断改写成人工审核。

不可回答问题无 gold 和 evidence，参考答案应说明无法确认；inspiration_chunk_ids 仅表示生成背景。知识库全局不可回答性仍需人工确认。多事实/商品比较题要求至少两个 Gold Chunk。relevance_complete=false 表示尚未穷尽相关 Chunk。

重新校验正式文件时可原地输出：

```powershell
& $py rag-eval/scripts/validate_dataset.py --dataset rag-eval/datasets/retrieval_dataset.jsonl --output rag-eval/datasets/retrieval_dataset.jsonl
```

数据集审核或修订后必须重新执行检索；程序通过文件 SHA256 拒绝混用旧结果。不要覆盖已审核文件。扩大到 150 题时使用新的目录，先复制同一份 chunks.jsonl 和 snapshot.json，再执行：

```powershell
& $py rag-eval/scripts/generate_dataset.py --size 150 --chunk-limit 607 --output rag-eval/datasets/full/retrieval_candidates.jsonl
& $py rag-eval/scripts/validate_dataset.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl --output rag-eval/datasets/full/retrieval_dataset.jsonl
& $py rag-eval/scripts/run_retrieval.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl --limit 150 --concurrency 2 --output rag-eval/results/full
& $py rag-eval/scripts/evaluate_retrieval.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl --results rag-eval/results/full
& $py rag-eval/scripts/generate_report.py --dataset rag-eval/datasets/full/retrieval_dataset.jsonl --results rag-eval/results/full
```

150 题目标配额依次为 fact 40、paraphrase 30、product_comparison 20、table 15、multi_fact 15、unanswerable 30。小样本按比例分配，允许无法可靠生成的题缺额。Ragas KnowledgeGraph 节点直接对应现有 Chunk；自定义 BaseSynthesizer 控制题型与证据，TestsetGenerator 负责调度。不使用默认分块变换，不对入库资料重新切块。按文档轮转选来源，但这不保证每个知识点均被覆盖。

## 四组实验与计分

- VECTOR_ONLY：现有 PgVectorRetriever Top15 → Top5。
- BM25_ONLY：现有 EsBm25Retriever Top15 → Top5。
- HYBRID_RRF：同题的两路 Top15 → 现有 RagService.rrf Top10 → Top5。
- HYBRID_RERANK：同一份 RRF Top10 → 现有 RagService.rerank → Top5。

可指定 --modes VECTOR_ONLY BM25_ONLY，或调整 --limit、--concurrency、--timeout。Java 执行器强制检查 15/15/10/5；RRF K、模型等记录在 run_manifest.json。不同问题之间并发，单题两路诊断串行。问题超时从批次提交算起，包含排队；底层 HTTP/数据库仍受各自超时限制，取消是尽力而为。

当 limit 小于数据集数量时按题型确定性轮转抽取，保证小规模评测覆盖不同题型，不会只截取排序靠前的一类题。实际问题 ID 与抽样策略保存在运行清单。

原始结果保存最终排名、正文、两路排名、RRF/Rerank 分数、两路全部召回与融合候选、分阶段时间和错误。重排异常直接失败，主指标计零。召回故障即使产生部分候选，也计为该模式失败；其他不依赖失败阶段的单路模式可正常评分。空召回可正常成功且得零分。

指标：Hit@1/3/5、已标注证据 Recall@5、MRR@5、二元 nDCG@5；Context Precision/Recall 调用 Ragas IDBasedContextPrecision/IDBasedContextRecall。ID Precision 是集合交集/返回数，并非 LLM Context Precision 或 Average Precision。失败和缺失记录保留在分母中，不能跳过。结构错误标签单列 unscorable_labels，不编造分数。

不可回答问题单列返回率，不进入普通 Recall。检索器没有拒答阈值或回答内容，非空召回不能直接定义为拒答失败。报告分别给出 all_exploratory、pending、approved 指标；无审核数据时正式结论为空。

--production-latency 另外真实调用 RagService.search，保留其端到端时间与结果。生产方法含双路并行及内部降级，当前返回类型不暴露降级状态，因此标记 COMPLETED_DEGRADATION_UNKNOWN。独立记录，不把诊断时间冒充生产延迟，也不把该次返回混入四模式质量评分。

Java 在运行前后核对 PG/ES 指纹，变化或核对失败则 snapshot_valid=false，报告无效；指纹检查不是跨存储事务快照。ES 指纹扫描当前整个索引，最多支持 10000 条，超限直接失败；更大规模需增加 PIT/search_after，不会静默截断。

## 产物

datasets/：chunks.jsonl、snapshot.json、候选集、正式可编辑数据集、generation_summary.json。

results/：retrieval_results.jsonl、retrieval_scores.jsonl、retrieval_metrics.csv、run_manifest.json、evaluation_summary.json、retrieval_report.md、retrieval_comparison.png、low_score_questions.jsonl。低分原因是规则推断，报告统一标记为疑似。

数据和报告默认 Git 忽略，只提交执行代码。生产代码、数据库表、前端均无需修改。

## 后续回答与 Agent 评测边界

可在 scripts/ 下新增独立 evaluate_answers.py，读取真实 AgentScope 运行记录中的 user_input、response、retrieved_contexts、reference；另行计算 Faithfulness、Factual Correctness、Answer Relevancy。Tool Call Accuracy、Agent Task Success 还需真实工具轨迹及人工任务期望。不要以本轮纯检索结果填造这些字段或分数。现有检索评分与原始结果协议无需改变。

## API 核实来源

2026-10-20 核查 PyPI 并固定 Ragas 0.4.3，结合安装包源代码核对实际签名：

0.4.3 顶层 ragas.metrics 导入会统一提示迁往 collections，但安装包的 collections 实际没有导出两个 ID 指标。本项目明确从该固定版本的 _context_precision / _context_recall 实现模块导入，调用其支持的 single_turn_ascore(SingleTurnSample)。这项兼容处理绑定 0.4.3；升级时需重新核对，不照搬提示中的不可用导入路径。requirements.lock.txt 同时固定实际安装的传递依赖。

- [Ragas PyPI](https://pypi.org/project/ragas/0.4.3/)
- [TestsetGenerator 与知识图谱](https://docs.ragas.io/en/stable/getstarted/rag_testset_generation/)
- [0.4.3 Synthesizer 源码](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/testset/synthesizers/base.py)
- [ID Context Precision](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/)
- [ID Context Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)

## 本地原文扩充评测：每种模式至少 100 道可回答题

新增 prepare_desktop_sources.py、generate_desktop_dataset.py、validate_desktop_dataset.py 和 run_desktop_evaluation.py。原文直接从指定本地目录读取，Markdown 保留全文，PDF 使用已有 PDFBox、旧 Word 使用已有 POI 依赖提取正文；不重新上传或分块。完整原文提供出题上下文，Gold 仍绑定知识库真实 Chunk，并逐条核对引用也存在于本地原文中。源文件指纹在执行前后核对。

```powershell
$env:PYTHONUTF8 = '1'
$py = './rag-eval/.venv/Scripts/python.exe'
# 新一轮请选新的目录，保留历史结果及人工审核记录。
& $py rag-eval/scripts/export_chunks.py --output rag-eval/datasets/desktop100
& $py rag-eval/scripts/prepare_desktop_sources.py --source-directory 'C:\Users\Lijianwen\Desktop\新建文件夹' --output rag-eval/datasets/desktop100
& $py rag-eval/scripts/generate_desktop_dataset.py --directory rag-eval/datasets/desktop100 --minimum-answerable 100 --answerable-target 120 --unanswerable-count 20 --concurrency 4
& $py rag-eval/scripts/run_desktop_evaluation.py --directory rag-eval/datasets/desktop100 --output rag-eval/results/desktop100 --concurrency 1 --production-latency
```

generate_desktop_dataset.py 在正常完成初始配额后，若有效可回答题不足目标，只根据结构和证据核对结果继续补题，不参考检索得分。未审核候选集可以使用 --resume 续生成缺额；包含已人工审核记录时拒绝覆盖。每道题仍保留 pending 状态，自动证据核对不能代替人工语义审核。

run_desktop_evaluation.py 对完整数据集执行四种模式。若有效可回答题不足 100 道，数量检查直接阻止执行；不会把不可回答题凑入 100 道的指标分母。结果数量、问题集合及知识库快照保持一致。原始文档有变化时标记结果无效。对应报告为 results/desktop100/retrieval_report.md，数据集为 datasets/desktop100/retrieval_dataset.jsonl。

本轮原文目录共 84 份文件，当前有效快照匹配到 77 份、607 个 Chunk。7 份配件指南未匹配到有效知识库记录，详见 datasets/desktop100/source_audit.json；不为未匹配来源伪造 Chunk ID。
