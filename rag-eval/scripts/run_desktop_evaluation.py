"""整批真实评测：每种模式至少 100 道可回答题，复用同一数据集和快照。"""
import argparse
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path
from common import EVAL, MODES, java, read_jsonl, write_json
from validate_desktop_dataset import check_sources


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=EVAL / "datasets/desktop100")
    parser.add_argument("--output", type=Path, default=EVAL / "results/desktop100")
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--production-latency", action="store_true")
    args = parser.parse_args()
    directory = args.directory.resolve()
    output = args.output.resolve()
    scripts = EVAL / "scripts"
    subprocess.run([sys.executable, str(scripts / "validate_desktop_dataset.py"), "--directory", str(directory)], check=True)
    dataset = directory / "retrieval_dataset.jsonl"
    questions = read_jsonl(dataset)
    answerable = sum(row["answerable"] is True and row["review_status"] != "rejected" for row in questions)
    if answerable < 100:
        raise ValueError("不足 100 道有效可回答题，拒绝缩减样本执行")
    java("retrieve", input=dataset, snapshot=directory / "snapshot.json", output=output,
         limit=len(questions), concurrency=args.concurrency, timeout_seconds=180, modes=",".join(MODES),
         production_latency=str(args.production_latency).lower())
    rows = read_jsonl(output / "retrieval_results.jsonl")
    manifest_path = output / "run_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    try:
        source_check = check_sources(directory, questions)
        manifest["source_snapshot_valid"] = source_check["source_files_valid"]
    except Exception as error:
        manifest["source_snapshot_valid"] = False
        manifest["source_check_error"] = type(error).__name__
        manifest["snapshot_valid"] = False
    counts = Counter(row["mode"] for row in rows)
    manifest["minimum_answerable_per_mode"] = 100
    manifest["answerable_per_mode"] = answerable
    manifest["mode_result_counts"] = dict(counts)
    write_json(manifest_path, manifest)
    if any(counts.get(mode, 0) != len(questions) for mode in MODES):
        raise ValueError("模式结果数量不完整，已保留原始记录")
    subprocess.run([sys.executable, str(scripts / "evaluate_retrieval.py"), "--dataset", str(dataset),
                    "--chunks", str(directory / "chunks.jsonl"), "--snapshot", str(directory / "snapshot.json"),
                    "--results", str(output)], check=True)
    subprocess.run([sys.executable, str(scripts / "generate_report.py"), "--dataset", str(dataset),
                    "--results", str(output)], check=True)
    print(f"四模式各完成 {len(questions)} 道问题，其中各有 {answerable} 道可回答题。")


if __name__ == "__main__":
    main()
