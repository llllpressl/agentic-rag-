"""从真实评测产物生成汇总、按题型明细、低分问题及图表。"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
from common import EVAL, read_jsonl, sha256, write_jsonl


def display(value, digits: int = 3) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def cell(value) -> str:
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=EVAL / "datasets/retrieval_dataset.jsonl")
    parser.add_argument("--results", type=Path, default=EVAL / "results")
    args = parser.parse_args()
    summary = json.loads((args.results / "evaluation_summary.json").read_text(encoding="utf-8"))
    manifest = json.loads((args.results / "run_manifest.json").read_text(encoding="utf-8"))
    if manifest["dataset_sha256"] != sha256(args.dataset):
        raise ValueError("数据集已改变，需重新执行检索和评分，不能生成旧报告")
    dataset = {row["id"]: row for row in read_jsonl(args.dataset)}
    scores = read_jsonl(args.results / "retrieval_scores.jsonl")
    raw = {(row["question_id"], row["mode"]): row for row in read_jsonl(args.results / "retrieval_results.jsonl")}
    overall = [row for row in summary["metrics"] if row["cohort"] == "all_exploratory" and row["category"] == "ALL"]
    snapshot = summary["snapshot"]
    started_at = datetime.fromisoformat(manifest["started_at"].replace("Z", "+00:00"))
    local_started_at = started_at.astimezone(ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d %H:%M:%S %z")
    lines = ["# RAG 检索评测报告", "", f"运行开始：{local_started_at}（上海时区）；Ragas {summary['ragas_version']}。", "",
        f"知识快照：{snapshot['document_count']} 篇有有效 Chunk 的文档，{snapshot['chunk_count']} 个 Chunk；"
        f"缺少向量 {snapshot['missing_embeddings']} 个。任务预期 84 篇，实际相差 {snapshot['document_count'] - 84} 篇，以数据库可用状态为准。",
        f"Embedding：{snapshot['query_embedding_model']}，{snapshot['embedding_dimensions']} 维；Rerank：{manifest['rerank_model']}。", "",
        f"快照前后核对：{'通过' if summary['snapshot_valid'] else '失败，本报告无效，禁止据此形成质量结论'}。",
        f"PG 快照 ID：`{manifest['snapshot_id']}`。ES 快照 ID：`{manifest['es_snapshot_id']}`。", "",
        f"本轮 {summary['selected_questions']} 题；人工审核 {summary['approved_questions']} 题，待审核 {summary['pending_questions']} 题。"
        f"应有 {summary['expected_records']} 条结果，实际 {summary['actual_records']} 条，缺失 {summary['missing_records']} 条。", "",
        "自动候选的标签和参考答案只支持探索性结论；结构核对与 LLM 自审均不构成人工 Ground Truth。"
        "本轮未编写或运行单元测试。实际检索评测按用户授权执行。", "",
        "## 四种模式对比（全部候选，探索性）", "",
        "| 模式 | 可评分题数 | Hit@1 | Hit@3 | Hit@5 | Recall@5 | MRR@5 | nDCG@5 | ID Precision | ID Recall | 诊断 P95 ms | 失败率 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in overall:
        values = [row["mode"], row["scored_answerable"]] + [display(row[key]) for key in [
            "hit_at_1", "hit_at_3", "hit_at_5", "recall_at_5", "mrr_at_5", "ndcg_at_5",
            "context_precision_id", "context_recall_id", "p95_diagnostic_ms", "failure_rate"]]
        lines.append("| " + " | ".join(str(value) for value in values) + " |")
    generation_file = args.dataset.parent / "generation_summary.json"
    audit_file = args.dataset.parent / "source_audit.json"
    if audit_file.exists():
        audit = json.loads(audit_file.read_text(encoding="utf-8"))
        source_lines = ["## 本地原文与知识库映射", "", f"原文目录：`{audit['source_directory']}`。",
            f"目录内 {audit['source_documents']} 份原文，匹配到 {audit['mapped_documents']} 份有效知识库文档、"
            f"{audit['mapped_chunks']} 个 Chunk。全文作为 Ragas 出题上下文，所有可回答题的证据同时核对原文和入库 Chunk。", ""]
        generated_documents = {item["doc_id"] for question in dataset.values() for item in question.get("source_documents", [])}
        evidence_documents = {item["doc_id"] for question in dataset.values() for item in question.get("evidence", [])}
        gold_chunks = {identifier for question in dataset.values() for identifier in question.get("gold_chunk_ids", [])}
        source_lines.extend([f"完整候选数据集实际涉及 {len(generated_documents)} 份原文，"
            f"可回答题引用 {len(evidence_documents)} 份文档的 {len(gold_chunks)} 个不同 Gold Chunk。", ""])
        missing = [item for item in audit["documents"] if item["mapping_status"] != "MAPPED_BY_NAME"]
        if missing:
            source_lines.extend(["当前快照未匹配到的原文如下，未为其伪造 Gold；这些文件不会计入可回答题来源：", ""])
            source_lines.extend(f"- {item['relative_path']}：{item['mapping_status']}" for item in missing)
        if manifest.get("minimum_answerable_per_mode"):
            source_lines.extend(["", f"本轮每种模式最低要求 {manifest['minimum_answerable_per_mode']} 道可回答题；"
                f"实际每种模式 {manifest['answerable_per_mode']} 道可回答题，另含不可回答题。",
                f"原文文件指纹前后核对：{'通过' if manifest.get('source_snapshot_valid') else '失败，本轮无效'}。"])
        source_lines.append("")
        position = lines.index("## 四种模式对比（全部候选，探索性）")
        lines[position:position] = source_lines
    if generation_file.exists():
        generation = json.loads(generation_file.read_text(encoding="utf-8"))
        generation_lines = ["## 数据集生成与缺额", "",
            f"目标 {generation['target']} 题，实际生成 {generation['raw_candidates']} 道候选，"
            f"结构与证据校验通过 {generation['valid_candidates']} 道。来源覆盖 "
            f"{generation['selected_documents']} 篇文档、{generation['selected_chunks']} 个既有 Chunk。", "",
            "| 题型 | 目标 | 有效候选 | 缺额 |", "|---|---:|---:|---:|"]
        for kind, count in generation["target_by_category"].items():
            generation_lines.append(f"| {kind} | {count} | {generation['valid_by_category'][kind]} | {generation['shortfalls'][kind]} |")
        reasons = list(dict.fromkeys(issue["reason"] for issue in generation["attempt_issues"]))
        generation_lines.extend(["", "生成跳过原因与结构错误记录保存在同目录的 generation_summary.json 和 retrieval_candidates.jsonl。"])
        for reason in reasons[:8]:
            generation_lines.append(f"- {reason}")
        if any("标题" in reason and "参数" in reason for reason in reasons):
            generation_lines.extend(["", "部分生成跳过理由指出候选 Chunk 仅含参数标题、缺少参数正文，"
                "疑似与分块边界或来源选择有关。该理由来自模型生成过程，未经人工验证，需对照原始文档及相邻 Chunk 核实。"])
        generation_lines.append("")
        position = lines.index("## 四种模式对比（全部候选，探索性）")
        lines[position:position] = generation_lines
    lines.extend(["", "![检索效果对比](retrieval_comparison.png)", "", "## 人工审核子集", ""])
    if not summary["approved_questions"]:
        lines.append("本轮无人工审核题目，尚无可声称基于人工标签的正式指标。")
    else:
        lines.extend(["| 模式 | 可评分题数 | Hit@5 | Recall@5 | MRR@5 | nDCG@5 |", "|---|---:|---:|---:|---:|---:|"])
        for row in summary["metrics"]:
            if row["cohort"] == "approved" and row["category"] == "ALL":
                lines.append(f"| {row['mode']} | {row['scored_answerable']} | {display(row['hit_at_5'])} | "
                             f"{display(row['recall_at_5'])} | {display(row['mrr_at_5'])} | {display(row['ndcg_at_5'])} |")
    lines.extend(["", "## 按题型汇总（探索性）", "", "| 题型 | 模式 | 总题数 | 可评分题数 | Hit@5 | Recall@5 | MRR@5 | 失败数 |",
                  "|---|---|---:|---:|---:|---:|---:|---:|"])
    for row in summary["metrics"]:
        if row["cohort"] == "all_exploratory" and row["category"] != "ALL":
            lines.append(f"| {row['category']} | {row['mode']} | {row['samples']} | {row['scored_answerable']} | "
                         f"{display(row['hit_at_5'])} | {display(row['recall_at_5'])} | {display(row['mrr_at_5'])} | {row['failed']} |")
    lines.extend(["", "## 计分口径与延迟", ""])
    lines.extend(f"- {note}" for note in summary["notes"])
    lines.extend(["- nDCG 使用二元相关性，理想排序长度为 min(5, 已标注相关 Chunk 数)。",
        "- 四种方案共享同题的 Vector Top15 与 BM25 Top15，RRF Top10，最终 Top5；Rerank 失败不回退计为成功。",
        "- 平均、P50、P95 诊断时间包含该模式所需阶段，双路是串行诊断，不能用于声称生产并行链路延迟。",
        "- 问题总超时以 question 阶段记录；底层错误仅记录类型，具体依赖的超时也可能包装为 IllegalStateException。",
        "- 前后指纹不能代替事务级跨 PG/ES 快照；运行期间须停止知识库写入，变化检查失败时本轮无效。", "",
        "| 模式 | 平均诊断 ms | P50 ms | P95 ms | 延迟样本数 | 不可回答题数 | 不可回答非空召回率 |",
        "|---|---:|---:|---:|---:|---:|---:|"])
    for row in overall:
        lines.append(f"| {row['mode']} | {display(row['mean_diagnostic_ms'])} | {display(row['p50_diagnostic_ms'])} | "
                     f"{display(row['p95_diagnostic_ms'])} | {row['latency_observations']} | {row['unanswerable_samples']} | "
                     f"{display(row['unanswerable_nonempty_rate'])} |")
    productions = {}
    for (identifier, mode), row in raw.items():
        if row.get("production"):
            productions[identifier] = row["production"]
    if productions:
        from evaluate_retrieval import percentile
        times = [row["elapsed_ms"] for row in productions.values()]
        failed = sum(row["status"] == "FAILED" for row in productions.values())
        lines.extend(["", f"另行调用真实 RagService.search：{len(times)} 次，平均 {display(sum(times)/len(times))} ms，"
            f"P50 {display(percentile(times, .5))} ms，P95 {display(percentile(times, .95))} ms，异常 {failed} 次。",
            "生产入口自行处理降级，现有返回类型不暴露降级标记，因此完成不等于各阶段均成功；此耗时不混入模式质量评分。"])
    else:
        lines.extend(["", "未采集生产入口端到端耗时；使用 --production-latency 可独立采集。"])
    lines.extend(["", "## 方案差异与阶段分析", ""])
    grouped = {row["mode"]: row for row in overall}
    for before, after, label in [("BM25_ONLY", "VECTOR_ONLY", "Vector 相对 BM25"),
                                  ("VECTOR_ONLY", "HYBRID_RRF", "RRF 相对 Vector"),
                                  ("BM25_ONLY", "HYBRID_RRF", "RRF 相对 BM25"),
                                  ("HYBRID_RRF", "HYBRID_RERANK", "Rerank 相对 RRF")]:
        if before in grouped and after in grouped and grouped[before]["mrr_at_5"] is not None and grouped[after]["mrr_at_5"] is not None:
            delta_mrr = grouped[after]["mrr_at_5"] - grouped[before]["mrr_at_5"]
            delta_hit = grouped[after]["hit_at_5"] - grouped[before]["hit_at_5"]
            lines.append(f"- {label}：Hit@5 变化 {delta_hit:+.3f}，MRR@5 变化 {delta_mrr:+.3f}。仅反映本轮问题及候选标签。")
    rerank_times = [row["stage_ms"]["rerank"] for row in scores if row["mode"] == "HYBRID_RERANK" and "rerank" in row["stage_ms"]]
    if rerank_times:
        lines.append(f"- 实际重排阶段额外时间：平均 {sum(rerank_times)/len(rerank_times):.3f} ms（{len(rerank_times)} 次，包含失败调用）。")
    lines.extend(["", "## 低分问题与失败记录", "", "以下原因均为规则推断的疑似原因，未经人工确认。"])
    low = []
    low_ids = set()
    for row in scores:
        if row["status"] != "SUCCESS" or (row["scorable_answerable"] and (row["recall_at_5"] < 1 or row["mrr_at_5"] < 1)):
            low_ids.add(row["question_id"])
    for identifier in sorted(low_ids):
        question = dataset[identifier]
        details = []
        reasons = set()
        gold = set(question["gold_chunk_ids"])
        for mode in manifest["modes"]:
            row = raw.get((identifier, mode), {"status": "MISSING", "results": []})
            details.append({"mode": mode, **row})
            if row["status"] != "SUCCESS":
                reasons.add("依赖调用失败、问题超时或记录缺失，见原始阶段错误")
            if gold and row["status"] == "SUCCESS":
                recalled = {str(item["id"]) for item in row.get("vector_candidates", []) + row.get("bm25_candidates", [])}
                fused = {str(item["id"]) for item in row.get("rrf_candidates", [])}
                final = {str(item["chunk_id"]) for item in row.get("results", [])}
                if mode.startswith("HYBRID"):
                    if not gold.issubset(recalled):
                        reasons.add("召回 Top15 未覆盖全部已标注证据")
                    elif not gold.issubset(fused):
                        reasons.add("RRF Top10 截断了已召回证据")
                    elif not gold.issubset(final):
                        reasons.add("最终 Top5 排名或截断未保留全部证据")
            if question["category"] == "table":
                reasons.add("表格题可能受表头、条件与数据行分块边界影响，需回原文核实")
        low.append({"question_id": identifier, "query": question["query"], "reference": question["reference"],
                    "gold_chunk_ids": question["gold_chunk_ids"], "evidence": question["evidence"],
                    "suspected_reasons": sorted(reasons), "modes": details})
        lines.extend(["", f"### {identifier}", "", f"Query：{question['query']}", "",
                      f"参考答案：{question['reference']}", "", f"Gold Chunk：`{', '.join(question['gold_chunk_ids'])}`；审核：{question['review_status']}。", "",
                      "| 模式 | 状态 | 实际 Top5（排名:ID / RRF / Rerank） | 阶段错误 |", "|---|---|---|---|"])
        for detail in details:
            retrieved = "; ".join(f"{hit['rank']}:{hit['chunk_id']} / {display(hit.get('rrf_score'))} / {display(hit.get('rerank_score'))}"
                                  for hit in detail.get("results", []))
            lines.append(f"| {detail['mode']} | {detail['status']} | {cell(retrieved)} | {cell(detail.get('stage_errors', detail.get('failure_stage', '')))} |")
        lines.append("\n疑似原因：" + "；".join(sorted(reasons) or ["已标注证据排名靠后，需人工比较问题、候选及标签"]) + "。")
    if not low:
        lines.append("\n本轮未筛出低分或失败问题；不能据此推断未覆盖题型的质量。")
    write_jsonl(args.results / "low_score_questions.jsonl", low)
    lines.extend(["", "## 原始产物", "", "- retrieval_results.jsonl：完整召回、融合、重排结果与正文、分数、阶段耗时。",
        "- retrieval_scores.jsonl：逐题评分，保留失败及缺失状态。",
        "- retrieval_metrics.csv：总体、审核状态、题型维度汇总。",
        "- low_score_questions.jsonl：四模式低分对照与疑似原因。", ""])
    (args.results / "retrieval_report.md").write_text("\n".join(lines), encoding="utf-8")
    chart(overall, args.results / "retrieval_comparison.png")
    print(f"报告已生成：{args.results / 'retrieval_report.md'}")


def chart(rows: list[dict], output: Path) -> None:
    os.environ.setdefault("MPLCONFIGDIR", str(EVAL / ".cache/matplotlib"))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    figure, axes = plt.subplots(1, 2, figsize=(13, 4.8), constrained_layout=True)
    labels = [row["mode"].replace("_", "\n") for row in rows]
    x = list(range(len(rows)))
    for index, (key, label) in enumerate([("hit_at_5", "Hit@5"), ("recall_at_5", "Recall@5"),
                                          ("mrr_at_5", "MRR@5"), ("ndcg_at_5", "nDCG@5")]):
        values = [float("nan") if row[key] is None else row[key] for row in rows]
        axes[0].bar([value + (index - 1.5) * .18 for value in x], values, width=.18, label=label)
    axes[0].set_ylim(0, 1.05)
    axes[0].set_title("Exploratory retrieval quality (candidate labels)")
    axes[0].legend(fontsize=8, loc="upper center", bbox_to_anchor=(.5, -.17), ncol=4, frameon=False)
    axes[1].bar(x, [float("nan") if row["p95_diagnostic_ms"] is None else row["p95_diagnostic_ms"] for row in rows], color="#315d87")
    axes[1].set_title("Serial diagnostic P95 (not production latency)")
    axes[1].set_ylabel("milliseconds")
    for axis in axes:
        axis.set_xticks(x, labels, fontsize=8)
        axis.grid(axis="y", alpha=.2)
        axis.set_axisbelow(True)
    figure.savefig(output, dpi=170)
    plt.close(figure)


if __name__ == "__main__":
    main()
