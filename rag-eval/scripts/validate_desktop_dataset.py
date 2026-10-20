"""核对本地文件指纹及每条证据的原文来源，再核对知识库 Chunk。"""
import argparse
import json
from pathlib import Path
from common import EVAL, read_jsonl, sha256, write_json, write_jsonl
from prepare_desktop_sources import source_text
from validate_dataset import validate


def check_sources(directory: Path, rows: list[dict]) -> dict:
    audit = json.loads((directory / "source_audit.json").read_text(encoding="utf-8"))
    root = Path(audit["source_directory"]).resolve()
    documents = read_jsonl(directory / "mapped_source_documents.jsonl")
    by_id = {document["doc_id"]: document for document in documents}
    file_errors = []
    for record in audit["documents"]:
        path = (root / record["relative_path"]).resolve()
        if not path.is_relative_to(root) or not path.is_file() or sha256(path) != record["file_sha256"]:
            file_errors.append(record["relative_path"])
    for row in rows:
        errors = []
        declared = {str(item["doc_id"]): item for item in row.get("source_documents", [])}
        if not declared:
            errors.append("missing_local_source_metadata")
        for document_id, metadata in declared.items():
            document = by_id.get(document_id)
            if (not document or metadata.get("file_sha256") != document["file_sha256"]
                    or metadata.get("relative_path") != document["relative_path"]):
                errors.append(f"source_document_mismatch:{document_id}")
        for evidence in row.get("evidence", []):
            document_id = str(evidence.get("doc_id", ""))
            document = by_id.get(document_id)
            quote = evidence.get("quote", "")
            if (document_id not in declared or not document or not isinstance(quote, str)
                    or not source_text(quote) or source_text(quote) not in source_text(document["content"])):
                errors.append(f"evidence_not_in_local_document:{evidence.get('chunk_id', '')}")
        if row["category"] == "product_comparison" and len({item["doc_id"] for item in row.get("evidence", [])}) < 2:
            errors.append("comparison_requires_two_distinct_documents")
        row["source_validation_errors"] = errors
    result = {"source_files_valid": not file_errors, "changed_or_missing_files": file_errors,
              "source_documents": audit["source_documents"], "mapped_documents": len(documents)}
    if file_errors:
        raise ValueError("本地原文已改变或缺失，请重新冻结来源；详情见文件指纹")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=EVAL / "datasets/desktop100")
    args = parser.parse_args()
    rows = read_jsonl(args.directory / "retrieval_dataset.jsonl")
    summary = check_sources(args.directory, rows)
    snapshot = json.loads((args.directory / "snapshot.json").read_text(encoding="utf-8"))
    if snapshot["chunks_sha256"] != sha256(args.directory / "chunks.jsonl"):
        raise ValueError("知识库 Chunk 文件已改变")
    rows = validate(rows, read_jsonl(args.directory / "chunks.jsonl"), snapshot["snapshot_id"])
    invalid = [row for row in rows if row["validation_status"] != "valid"]
    summary.update({"questions": len(rows), "valid": len(rows) - len(invalid),
                    "invalid_ids": [row["id"] for row in invalid],
                    "answerable": sum(row["answerable"] and row["validation_status"] == "valid" for row in rows)})
    write_json(args.directory / "source_validation.json", summary)
    write_jsonl(args.directory / "retrieval_dataset.jsonl", rows)
    print(f"原文及 Chunk 双重核对：{summary['valid']}/{len(rows)}，有效可回答题 {summary['answerable']}。")
    if invalid or summary["answerable"] < 100:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
