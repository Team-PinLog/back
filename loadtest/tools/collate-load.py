"""부하 실행 산출물(artifacts/load/*)을 보고서용 markdown 표로 모은다.

사용: PYTHONUTF8=1 python tools/collate-load.py <artifacts/load 경로>

k6 --summary-export의 metrics 키는 'http_req_duration{kind:map}' 형태다(태그별 서브메트릭).
"""
import json
import sys
from pathlib import Path

ORDER = [
    "read-smoke", "read-average", "read-stress", "read-spike",
    "write-smoke", "write-average", "write-stress", "write-spike",
    "mixed-average", "mixed-stress",
]


def load_summary(run_dir: Path):
    f = run_dir / "summary.json"
    if not f.exists():
        return None
    return json.loads(f.read_text(encoding="utf-8"))


def duration_rows(name: str, summary) -> list[str]:
    # k6 Trend 서브메트릭에는 count가 없다(Counter/Rate만 count를 갖는다). 그래서 부류별
    # 요청 수 열을 두지 않고, med=p50·p95·p99만 싣는다. 전역 요청 수·실패율은 마지막 행에.
    rows = []
    metrics = summary.get("metrics", {})
    total_reqs = metrics.get("http_reqs", {}).get("count", 0)
    failed = metrics.get("http_req_failed", {}).get("value", 0.0)
    for key, m in sorted(metrics.items()):
        if not key.startswith("http_req_duration{"):
            continue
        kind = key[key.index("{") + 1 : -1].replace("kind:", "")
        if "expected_response" in kind:
            continue
        rows.append(
            f"| {name} | {kind} | {m.get('med', 0):.1f} | {m.get('p(95)', 0):.1f} "
            f"| {m.get('p(99)', 0):.1f} |"
        )
    rows.append(
        f"| {name} | (전체 요청) | {int(total_reqs)}건 | 실패율 {failed * 100:.2f}% | — |"
    )
    return rows


def metric_peaks(run_dir: Path):
    f = run_dir / "metrics.csv"
    if not f.exists():
        return None
    peaks = {"hikari_active": 0.0, "hikari_pending": 0.0, "jvm_threads": 0.0, "heap_used_bytes": 0.0}
    lines = f.read_text(encoding="utf-8").strip().splitlines()
    header = lines[0].split(",")
    for line in lines[1:]:
        vals = dict(zip(header, line.split(",")))
        for k in peaks:
            try:
                peaks[k] = max(peaks[k], float(vals.get(k, 0) or 0))
            except ValueError:
                pass
    return peaks


def main() -> None:
    root = Path(sys.argv[1])
    print("## 실행별 부류 지연\n")
    print("| 실행 | 부류 | p50(ms) | p95(ms) | p99(ms) |")
    print("| --- | --- | --- | --- | --- |")
    for name in ORDER:
        summary = load_summary(root / name)
        if summary is None:
            print(f"| {name} | 미실행 | — | — | — |")
            continue
        for row in duration_rows(name, summary):
            print(row)

    print("\n## 실행별 메트릭 최고점\n")
    print("| 실행 | hikari 활성 최대 | hikari 대기 최대 | 스레드 최대 | 힙 최대(MB) |")
    print("| --- | --- | --- | --- | --- |")
    for name in ORDER:
        peaks = metric_peaks(root / name)
        if peaks is None:
            print(f"| {name} | — | — | — | — |")
            continue
        print(
            f"| {name} | {peaks['hikari_active']:.0f} | {peaks['hikari_pending']:.0f} "
            f"| {peaks['jvm_threads']:.0f} | {peaks['heap_used_bytes'] / 1048576:.0f} |"
        )


if __name__ == "__main__":
    main()
