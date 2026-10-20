"""只读提取本地原文，并核对其与当前知识库文档、Chunk 的映射。"""
import argparse
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path
from common import EVAL, java, read_jsonl, sha256, write_json, write_jsonl


def source_text(value: str) -> str:
    """忽略排版标记和空白，保留小数点、数值区间和单位等事实字符。"""
    normalized = unicodedata.normalize("NFKC", value).casefold()
    normalized = re.sub(r"(?m)^\s*[-+]\s+", "", normalized)
    return re.sub(r"[\s\x00-\x1f\x7f|`*_#]+", "", normalized)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=EVAL / "datasets/desktop100")
    args = parser.parse_args()
    snapshot = json.loads((args.output / "snapshot.json").read_text(encoding="utf-8"))
    if sha256(args.output / "chunks.jsonl") != snapshot["chunks_sha256"]:
        raise ValueError("知识库 Chunk 导出文件与快照不一致")
    java("sources", source_directory=args.source_directory.resolve(), output=args.output.resolve())
    sources = read_jsonl(args.output / "source_documents.jsonl")
    chunks = read_jsonl(args.output / "chunks.jsonl")
    by_name = {}
    for chunk in chunks:
        by_name.setdefault(chunk["doc_name"].casefold(), {}).setdefault(str(chunk["doc_id"]), []).append(chunk)
    records = []
    mapped = []
    for source in sources:
        groups = by_name.get(source["doc_name"].casefold(), {})
        record = {key: source[key] for key in ["relative_path", "doc_name", "file_size", "file_sha256", "parse_status"]}
        if source["parse_status"] != "SUCCESS":
            record["mapping_status"] = "SOURCE_PARSE_FAILED"
        elif len(groups) == 0:
            record["mapping_status"] = "NO_ACTIVE_KB_DOCUMENT"
        elif len(groups) > 1:
            record["mapping_status"] = "AMBIGUOUS_KB_DOCUMENT"
        else:
            document_id, document_chunks = next(iter(groups.items()))
            document_chunks.sort(key=lambda chunk: chunk["chunk_index"])
            record["mapping_status"] = "MAPPED_BY_NAME"
            record["doc_id"] = document_id
            record["chunk_count"] = len(document_chunks)
            record["content_verification"] = "生成后逐条证据校验其同时属于原文及已入库 Chunk"
            # 全文仅作为生成上下文；Gold 必须绑定现有 Chunk，且引用原文可核对。
            mapped.append({**source, "doc_id": document_id, "kb_id": document_chunks[0]["kb_id"],
                           "chunks": document_chunks, "normalized_content": source_text(source["content"])})
        records.append(record)
    write_jsonl(args.output / "mapped_source_documents.jsonl", mapped)
    write_json(args.output / "source_audit.json", {
        "source_directory": str(args.source_directory.resolve()), "snapshot_id": snapshot["snapshot_id"],
        "source_documents": len(sources), "mapped_documents": len(mapped),
        "mapped_chunks": sum(len(document["chunks"]) for document in mapped),
        "status_counts": dict(Counter(record["mapping_status"] for record in records)),
        "documents": records,
        "note": "文件名映射不代表全文完全一致；每道可回答题的证据还须通过原文与 Chunk 双重核对。未匹配文件不伪造 Gold。",
    })
    print(f"原文 {len(sources)} 份，映射到有效知识库文档 {len(mapped)} 份。")


if __name__ == "__main__":
    main()
