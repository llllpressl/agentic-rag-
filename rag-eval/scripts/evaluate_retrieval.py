"""Ragas ID 指标与确定性排名指标；失败计零，人工/候选标签分别汇总。"""
from __future__ import annotations

import argparse
import asyncio
import csv
import json
import math
import os
import platform
from pathlib import Path

os.environ.setdefault("RAGAS_DO_NOT_TRACK", "true")
from common import EVAL, read_jsonl, sha256, write_json, write_jsonl
from validate_dataset import validate

QUALITY = ["hit_at_1", "hit_at_3", "hit_at_5", "recall_at_5", "mrr_at_5", "ndcg_at_5",
           "context_precision_id", "context_recall_id"]


def percentile(values: list[float], proportion: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * proportion
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


async def score_ids(retrieved: list[str], gold: list[str]) -> dict:
    from ragas.dataset_schema import SingleTurnSample
    # 0.4.3 的 collections 并未导出这两个类；按固定版本定位实际实现。
    # 不采用 ragas.metrics 顶层给出的不可用迁移路径，详见 README 的 API 说明。
    from ragas.metrics._context_precision import IDBasedContextPrecision
    from ragas.metrics._context_recall import IDBasedContextRecall

    # 空召回显式计零，避免 Precision 的空分母造成 NaN。
    if not retrieved:
        return {key: 0.0 for key in QUALITY}
    relevant = set(gold)
    positions = [rank for rank, chunk_id in enumerate(retrieved, 1) if chunk_id in relevant]
    gain = sum(1 / math.log2(rank + 1) for rank in positions)
    ideal = sum(1 / math.log2(rank + 1) for rank in range(1, min(5, len(relevant)) + 1))
    sample = SingleTurnSample(retrieved_context_ids=retrieved, reference_context_ids=gold)
    precision = await IDBasedContextPrecision().single_turn_ascore(sample)
    recall = await IDBasedContextRecall().single_turn_ascore(sample)
    return {
        "hit_at_1": float(any(rank <= 1 for rank in positions)),
        "hit_at_3": float(any(rank <= 3 for rank in positions)),
        "hit_at_5": float(bool(positions)),
        "recall_at_5": len(set(retrieved) & relevant) / len(relevant),
        "mrr_at_5": 1 / positions[0] if positions else 0.0,
        "ndcg_at_5": gain / ideal if ideal else 0.0,
        "context_precision_id": float(precision), "context_recall_id": float(recall),
    }


def aggregate(rows: list[dict], mode: str, cohort: str, category: str) -> dict:
    eligible = [row for row in rows if row["scorable_answerable"]]
    times = [row["diagnostic_elapsed_ms"] for row in rows if row["diagnostic_elapsed_ms"] is not None]
    unanswered = [row for row in rows if row["answerable"] is False and row["label_valid"]]
    result = {
        "mode": mode, "cohort": cohort, "category": category,
        "samples": len(rows), "scored_answerable": len(eligible),
        "unscorable_labels": sum(not row["label_valid"] for row in rows),
        "failed": sum(row["status"] != "SUCCESS" for row in rows),
        "failure_rate": sum(row["status"] != "SUCCESS" for row in rows) / len(rows) if rows else None,
        "mean_diagnostic_ms": sum(times) / len(times) if times else None,
        "p50_diagnostic_ms": percentile(times, 0.5), "p95_diagnostic_ms": percentile(times, 0.95),
        "latency_observations": len(times), "unanswerable_samples": len(unanswered),
        "unanswerable_nonempty_rate": (sum(row["retrieved_count"] > 0 for row in unanswered) / len(unanswered)) if unanswered else None,
    }
    for key in QUALITY:
        result[key] = sum(row[key] for row in eligible) / len(eligible) if eligible else None
    return result


async def evaluate(args: argparse.Namespace) -> None:
    manifest = json.loads((args.results / "run_manifest.json").read_text(encoding="utf-8"))
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    if snapshot.get("chunks_sha256") != sha256(args.chunks):
        raise ValueError("Chunk 文件与导出快照不一致")
    if manifest["dataset_sha256"] != sha256(args.dataset):
        raise ValueError("数据集已变更，不能与旧检索结果混算；请重新执行检索")
    dataset = validate(read_jsonl(args.dataset), read_jsonl(args.chunks), snapshot["snapshot_id"])
    by_id = {row["id"]: row for row in dataset}
    selected = manifest["question_ids"]
    if len(set(selected)) != len(selected) or not set(selected).issubset(by_id):
        raise ValueError("运行清单与数据集的问题 ID 不一致")
    originals = read_jsonl(args.results / "retrieval_results.jsonl")
    records = {}
    for row in originals:
        key = (row["question_id"], row["mode"])
        if key in records or key[0] not in selected or key[1] not in manifest["modes"]:
            raise ValueError("检索结果包含重复或清单外记录")
        if row.get("snapshot_id") != snapshot["snapshot_id"] or row.get("query") != by_id[key[0]]["query"]:
            raise ValueError("结果快照或问题正文与数据集不一致")
        records[key] = row
    scored = []
    for question_id in selected:
        question = by_id[question_id]
        for mode in manifest["modes"]:
            raw = records.get((question_id, mode), {"status": "MISSING", "results": []})
            hits = raw.get("results", [])[:5]
            ids = [str(hit["chunk_id"]) for hit in hits]
            if len(set(ids)) != len(ids):
                raise ValueError("检索结果包含重复 Chunk ID，拒绝重复计分")
            status = raw["status"]
            valid = question["validation_status"] == "valid" and question["review_status"] != "rejected"
            scorable = valid and question["answerable"] is True
            scores = {key: None for key in QUALITY}
            if scorable:
                # 失败即使保留部分候选，主质量指标仍计零；原始候选继续用于诊断。
                scores = await score_ids(ids if status == "SUCCESS" else [], question["gold_chunk_ids"])
            row = {
                "question_id": question_id, "mode": mode, "category": question["category"],
                "review_status": question["review_status"], "answerable": question["answerable"],
                "label_valid": valid, "validation_errors": question["validation_errors"],
                "scorable_answerable": scorable, "status": status, "retrieved_count": len(ids),
                "diagnostic_elapsed_ms": raw.get("diagnostic_elapsed_ms"),
                "stage_ms": raw.get("stage_ms", {}), "stage_errors": raw.get("stage_errors", {}),
                **scores,
            }
            scored.append(row)
    metrics = []
    for mode in manifest["modes"]:
        mode_rows = [row for row in scored if row["mode"] == mode]
        for cohort in ["all_exploratory", "approved", "pending"]:
            cohort_rows = mode_rows if cohort == "all_exploratory" else [row for row in mode_rows if row["review_status"] == cohort]
            metrics.append(aggregate(cohort_rows, mode, cohort, "ALL"))
            for category in sorted({row["category"] for row in cohort_rows}):
                metrics.append(aggregate([row for row in cohort_rows if row["category"] == category], mode, cohort, category))
    write_jsonl(args.results / "retrieval_scores.jsonl", scored)
    with (args.results / "retrieval_metrics.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(metrics[0]))
        writer.writeheader()
        writer.writerows(metrics)
    write_json(args.results / "evaluation_summary.json", {
        "snapshot_valid": manifest.get("snapshot_valid", False), "snapshot": snapshot,
        "selected_questions": len(selected), "expected_records": len(selected) * len(manifest["modes"]),
        "actual_records": len(originals), "missing_records": len(scored) - len(originals),
        "approved_questions": sum(by_id[identifier]["review_status"] == "approved" for identifier in selected),
        "pending_questions": sum(by_id[identifier]["review_status"] == "pending" for identifier in selected),
        "metrics": metrics,
        "python_version": platform.python_version(),
        "ragas_version": __import__("importlib.metadata", fromlist=["version"]).version("ragas"),
        "notes": ["Recall 只针对已标注相关 Chunk；未声称穷尽全部相关证据。",
                  "失败与缺失样本在可评分问题的分母中并计零。",
                  "Context Precision / Recall 为 Ragas 基于 Chunk ID 的集合指标，不是 LLM 判断指标。",
                  "不可回答题仅统计返回率；返回证据不等于回答错误或拒答失败。"],
    })
    print(f"已计算 {len(scored)} 条评分；缺失记录 {len(scored) - len(originals)} 条，均保留计数。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=EVAL / "datasets/retrieval_dataset.jsonl")
    parser.add_argument("--chunks", type=Path, default=EVAL / "datasets/chunks.jsonl")
    parser.add_argument("--snapshot", type=Path, default=EVAL / "datasets/snapshot.json")
    parser.add_argument("--results", type=Path, default=EVAL / "results")
    args = parser.parse_args()
    asyncio.run(evaluate(args))


if __name__ == "__main__":
    main()
