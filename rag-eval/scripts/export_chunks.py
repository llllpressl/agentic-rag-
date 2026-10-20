"""通过独立 Java CLI 复用 Spring 数据源配置，导出只读 PG 快照。"""
import argparse
from pathlib import Path
from common import EVAL, java


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=EVAL / "datasets")
    args = parser.parse_args()
    java("export", output=args.output.resolve())


if __name__ == "__main__":
    main()
