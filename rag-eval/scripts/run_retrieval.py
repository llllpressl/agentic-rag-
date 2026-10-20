"""运行真实 Java 四模式检索；无公开 HTTP 评测接口。"""
import argparse
from pathlib import Path
from common import EVAL, MODES, java


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=EVAL / "datasets/retrieval_dataset.jsonl")
    parser.add_argument("--snapshot", type=Path, default=EVAL / "datasets/snapshot.json")
    parser.add_argument("--output", type=Path, default=EVAL / "results")
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--modes", nargs="+", choices=MODES, default=MODES)
    parser.add_argument("--production-latency", action="store_true")
    args = parser.parse_args()
    java("retrieve", input=args.dataset.resolve(), snapshot=args.snapshot.resolve(),
         output=args.output.resolve(), limit=args.limit, concurrency=args.concurrency,
         timeout_seconds=args.timeout, modes=",".join(args.modes),
         production_latency=str(args.production_latency).lower())


if __name__ == "__main__":
    main()
