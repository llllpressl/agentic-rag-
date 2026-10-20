"""结构、来源和审核状态核对；通过此程序不等于人工确认答案。"""
import argparse
import json
import re
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path
from common import CATEGORIES, EVAL, read_jsonl, sha256, write_json, write_jsonl


def normalize(text: str) -> str:
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", text).casefold())


def validate(rows: list[dict], chunks: list[dict], snapshot_id: str) -> list[dict]:
    sources = {}
    for chunk in chunks:
        identifier = str(chunk["chunk_id"])
        if identifier in sources:
            raise ValueError("Chunk 快照包含重复 ID")
        sources[identifier] = chunk
    identifiers = set()
    queries = []
    for row in rows:
        issues = []
        issues.extend(row.get("source_validation_errors", []))
        identifier = row.get("id")
        query = row.get("query", "")
        answer = row.get("reference", "")
        if not isinstance(identifier, str) or not identifier or identifier in identifiers:
            issues.append("missing_or_duplicate_id")
        identifiers.add(str(identifier))
        if not isinstance(query, str) or not query.strip():
            issues.append("empty_query")
            query = ""
        if not isinstance(answer, str) or not answer.strip():
            issues.append("empty_reference")
        if row.get("category") not in CATEGORIES:
            issues.append("unknown_category")
        if row.get("snapshot_id") != snapshot_id:
            issues.append("snapshot_mismatch")
        normalized = normalize(query)
        for previous_id, previous in queries:
            if normalized == previous or SequenceMatcher(None, normalized, previous).ratio() >= 0.92:
                issues.append(f"duplicate_query:{previous_id}")
                break
        queries.append((identifier, normalized))
        gold = row.get("gold_chunk_ids", [])
        evidence = row.get("evidence", [])
        if not isinstance(gold, list) or not isinstance(evidence, list):
            issues.append("invalid_source_shape")
            gold, evidence = [], []
        gold = [str(value) for value in gold]
        if len(set(gold)) != len(gold):
            issues.append("duplicate_gold_chunk")
        if row.get("answerable") is True:
            if not gold:
                issues.append("answerable_without_gold")
            if row.get("category") in {"multi_fact", "product_comparison"} and len(set(gold)) < 2:
                issues.append("multi_fact_requires_multiple_chunks")
        elif row.get("answerable") is False:
            if gold or evidence:
                issues.append("unanswerable_has_gold")
            if row.get("category") != "unanswerable":
                issues.append("unanswerable_category_mismatch")
        else:
            issues.append("answerability_not_boolean")
        if row.get("category") == "unanswerable" and row.get("answerable") is not False:
            issues.append("unanswerable_category_mismatch")
        traced = set()
        for item in evidence:
            if not isinstance(item, dict):
                issues.append("invalid_evidence_shape")
                continue
            chunk_id = str(item.get("chunk_id", ""))
            chunk = sources.get(chunk_id)
            quote = item.get("quote", "")
            if chunk is None:
                issues.append(f"unknown_evidence_chunk:{chunk_id}")
            elif (not isinstance(quote, str) or not quote.strip()
                  or quote not in chunk["content"]
                  or str(item.get("doc_id", "")) != str(chunk["doc_id"])
                  or item.get("doc_name") != chunk["doc_name"]):
                issues.append(f"untraceable_evidence:{chunk_id}")
            else:
                traced.add(chunk_id)
        for chunk_id in gold:
            if chunk_id not in sources:
                issues.append(f"unknown_gold_chunk:{chunk_id}")
            if chunk_id not in traced:
                issues.append(f"gold_without_evidence:{chunk_id}")
        if traced - set(gold):
            issues.append("evidence_not_in_gold")
        review = row.get("review_status", "pending")
        if review not in {"pending", "approved", "rejected"}:
            issues.append("unknown_review_status")
        if review == "approved" and (not row.get("reviewed_by") or not row.get("reviewed_at")):
            issues.append("approval_missing_human_audit_fields")
        row["validation_errors"] = issues
        row["validation_status"] = "valid" if not issues else "invalid"
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=EVAL / "datasets/retrieval_candidates.jsonl")
    parser.add_argument("--chunks", type=Path, default=EVAL / "datasets/chunks.jsonl")
    parser.add_argument("--snapshot", type=Path, default=EVAL / "datasets/snapshot.json")
    parser.add_argument("--output", type=Path, default=EVAL / "datasets/retrieval_dataset.jsonl")
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    if snapshot.get("chunks_sha256") != sha256(args.chunks):
        raise ValueError("Chunk 文件与导出快照不一致")
    snapshot_id = snapshot["snapshot_id"]
    rows = validate(read_jsonl(args.dataset), read_jsonl(args.chunks), snapshot_id)
    write_jsonl(args.output, rows)
    valid = sum(row["validation_status"] == "valid" for row in rows)
    write_json(args.output.with_suffix(".validation.json"), {"total": len(rows), "valid": valid, "invalid": len(rows) - valid})
    print(f"结构核对：有效 {valid}/{len(rows)}；审核状态保留，未自动批准。")
    if valid != len(rows):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
