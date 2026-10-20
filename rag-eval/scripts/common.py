"""文件协议、Spring 本机配置读取与 Java CLI 调用；不输出凭据。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "rag-eval"
MODES = ["VECTOR_ONLY", "BM25_ONLY", "HYBRID_RRF", "HYBRID_RERANK"]
CATEGORIES = {
    "fact": 40,
    "paraphrase": 30,
    "product_comparison": 20,
    "table": 15,
    "multi_fact": 15,
    "unanswerable": 30,
}


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8-sig") as handle:
        for number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path.name}:{number} 不是合法 JSON") from error
            if not isinstance(row, dict):
                raise ValueError(f"{path.name}:{number} 必须为对象")
            rows.append(row)
    return rows


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def spring_settings() -> dict:
    import yaml

    settings = {}

    def flatten(value: dict, prefix: str = "") -> None:
        for key, item in value.items():
            name = f"{prefix}.{key}" if prefix else key
            if isinstance(item, dict):
                flatten(item, name)
            else:
                settings[name] = item

    for name in ["agenticrag.yaml", "agenticrag-local.yaml"]:
        path = ROOT / "bootstrap/src/main/resources" / name
        if path.exists():
            flatten(yaml.safe_load(path.read_text(encoding="utf-8")) or {})
    for name, value in settings.items():
        if isinstance(value, str):
            settings[name] = re.sub(
                r"\$\{([A-Za-z_][A-Za-z_0-9]*)(?::([^}]*))?}",
                lambda match: os.environ.get(match[1], match[2] or ""), value,
            )
    return settings


def llm_settings() -> dict:
    settings = spring_settings()
    result = {
        "api_key": os.environ.get("RAG_EVAL_API_KEY") or settings.get("agent-ai.deepseek.api-key", ""),
        "base_url": os.environ.get("RAG_EVAL_BASE_URL") or settings.get("agent-ai.deepseek.url", ""),
        "model": os.environ.get("RAG_EVAL_MODEL") or settings.get("agent.chat.model", ""),
    }
    if not result["api_key"] or not result["model"]:
        raise ValueError("缺少评测模型配置；请配置 RAG_EVAL_API_KEY / RAG_EVAL_MODEL 或项目本机配置")
    return result


def java(action: str, **options) -> None:
    jar = EVAL / "java/target/rag-eval-runner-0.0.1-SNAPSHOT.jar"
    if not jar.exists():
        raise FileNotFoundError("请先执行 rag-eval/setup.ps1 构建独立 Java 执行器")
    command = ["java", "-Dfile.encoding=UTF-8", "-jar", str(jar), f"--eval.action={action}"]
    for key, value in options.items():
        command.append(f"--eval.{key.replace('_', '-')}={value}")
    command.extend([
        "--rag.search.vector-top-k=15", "--rag.search.bm25-top-k=15",
        "--rag.search.fusion-top-k=10", "--rag.search.final-top-k=5",
    ])
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        # 不转发 Spring 启动输出，避免第三方驱动将 URL/凭据带到终端。
        safe_lines = [line for line in result.stderr.splitlines() if line.startswith("EVALUATION_FAILED ")]
        raise RuntimeError(safe_lines[-1] if safe_lines else "Java 评测启动失败，请核对配置、数据库和依赖服务")
    for line in result.stdout.splitlines():
        if line.startswith(("EXPORT_COMPLETED ", "RETRIEVAL_COMPLETED ", "SOURCES_COMPLETED ")):
            print(line)
