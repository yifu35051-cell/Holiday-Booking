"""Build offline HTML, SVG views and planning CSVs from the design document.

Requires Python markdown; SVG diagrams use local explicit layouts.
No application implementation or business tests are executed by this script.
"""

from __future__ import annotations

import csv
import hashlib
import html
import json
import re
from pathlib import Path

import markdown
from svg_diagrams import render_all


DOCS = Path(__file__).resolve().parents[1]
ROOT = DOCS.parent.parent
SOURCE = DOCS / 'Holiday-Booking-开发技术文档.md'
ASSETS = DOCS / 'assets'
DIAGRAM_TITLES = [
    '部署与系统调用关系',
    '预订与支付时序',
    '预订状态迁移',
    '核心类关系',
    '数据库核心关系',
]


def render_diagrams(source: str) -> list[Path]:
    return render_all(source, ASSETS, DIAGRAM_TITLES)


def table_rows(source: str, start_heading: str, end_heading: str) -> list[list[str]]:
    part = source.split(start_heading, 1)[1].split(end_heading, 1)[0]
    rows = []
    for line in part.splitlines():
        if line.startswith('|') and not re.match(r'^\|[\s:|\-]+\|$', line):
            rows.append([value.strip() for value in line.strip().strip('|').split('|')])
    return rows


def write_csv(name: str, rows: list[list[str]], extra_headers: list[str], extras: list[str]) -> None:
    if not rows:
        raise ValueError(f'No rows found for {name}')
    with (DOCS / name).open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(rows[0] + extra_headers)
        writer.writerows(row + extras for row in rows[1:])


def build_html(source: str, diagrams: list[Path]) -> None:
    counter = 0

    def diagram(match: re.Match[str]) -> str:
        nonlocal counter
        index = counter
        counter += 1
        svg = diagrams[index].read_text(encoding='utf-8')
        svg = re.sub(r'<\?xml[^>]*\?>', '', svg)
        svg = svg.replace('my-svg', f'diagram-{index + 1:02d}')
        return (
            f'<figure class="diagram" aria-label="{DIAGRAM_TITLES[index]}">'
            f'<div class="diagram-scroll">{svg}</div>'
            f'<figcaption>图 {index + 1} · {DIAGRAM_TITLES[index]} '
            f'<a href="assets/{diagrams[index].name}">单独打开矢量图</a></figcaption>'
            f'<details><summary>查看 Mermaid 源码</summary><pre><code>'
            f'{html.escape(match.group(1))}</code></pre></details></figure>'
        )

    rendered_source = re.sub(r'^```mermaid\n(.*?)\n```', diagram, source, flags=re.M | re.S)
    md = markdown.Markdown(
        extensions=['tables', 'fenced_code', 'toc', 'sane_lists'],
        extension_configs={'toc': {'toc_depth': '2-2'}},
    )
    body = md.convert(rendered_source)
    body = body.replace('<table>', '<div class="table-scroll"><table>')
    body = body.replace('</table>', '</table></div>')
    css = '''
:root{--ink:#182a3a;--muted:#5a6b7a;--accent:#086b80;--line:#dce4eb;--paper:#fff;--back:#f3f6f9}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}
body{margin:0;background:var(--back);color:var(--ink);font:16px/1.85 "Microsoft YaHei","Segoe UI",Arial,sans-serif}
a{color:var(--accent);text-underline-offset:3px}a:hover{color:#034457}
.layout{max-width:1680px;margin:auto;display:grid;grid-template-columns:270px minmax(0,1fr);gap:28px;padding:24px}
aside{position:sticky;top:24px;align-self:start;max-height:calc(100vh - 48px);overflow:auto;padding:20px 18px;background:var(--paper);border:1px solid var(--line);border-radius:12px;font-size:13px;line-height:1.65}
.brand{font-weight:750;font-size:18px;line-height:1.4;margin-bottom:8px}.small{color:var(--muted);font-size:12px}
aside ul{list-style:none;padding:0}aside li{margin:10px 0}aside a{text-decoration:none}
main{min-width:0;background:var(--paper);border:1px solid var(--line);border-radius:14px;padding:38px 44px 60px}
.ribbon{font-size:13px;color:var(--accent);font-weight:600;letter-spacing:.04em;border-bottom:1px solid var(--line);padding-bottom:14px;margin-bottom:24px}
h1{font-size:32px;line-height:1.4;letter-spacing:-.02em;margin:0 0 18px}h2{font-size:25px;line-height:1.5;margin:54px 0 18px;padding-top:12px;border-top:2px solid var(--line)}h3{font-size:20px;line-height:1.5;margin:28px 0 12px}
p{margin:13px 0}li{margin:7px 0}strong{font-weight:700}code{font-family:Consolas,"Microsoft YaHei",monospace;font-size:.9em;background:#f0f4f7;border-radius:3px;padding:2px 4px;overflow-wrap:anywhere}
pre{background:#142536;color:#eaf1f7;padding:20px;border-radius:9px;overflow:auto;line-height:1.6;font-size:13px}pre code{background:none;color:inherit;padding:0;overflow-wrap:normal}
.table-scroll{overflow:auto;border:1px solid var(--line);border-radius:8px;margin:20px 0}table{width:100%;border-collapse:collapse;font-size:14px;line-height:1.7}th,td{padding:12px 14px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top;min-width:110px}th{background:#eaf1f5;font-weight:700;color:#153e50}tr:last-child td{border-bottom:none}tbody tr:nth-child(even){background:#f8fafb}
.diagram{margin:24px 0;padding:20px;border:1px solid var(--line);border-radius:10px;background:white}.diagram-scroll{overflow:auto}.diagram svg{display:block;margin:auto;width:100%;max-width:100%;height:auto}.diagram figcaption{font-size:13px;color:var(--muted);margin-top:15px}.diagram figcaption a{float:right}details{font-size:13px;margin-top:12px}summary{cursor:pointer;color:var(--accent)}
.foot{margin-top:48px;padding-top:16px;border-top:1px solid var(--line);font-size:12px;color:var(--muted)}
@media(max-width:1100px){.layout{grid-template-columns:220px minmax(0,1fr);gap:16px;padding:14px}main{padding:26px}table{min-width:720px}}
@media(max-width:780px){.layout{display:block;padding:10px}aside{position:static;max-height:270px;margin-bottom:15px}main{padding:22px 16px}h1{font-size:26px}h2{font-size:22px}.diagram{padding:12px}.diagram figcaption a{float:none;display:block}}
@media print{body{background:#fff;font-size:11pt}.layout{display:block;padding:0}aside,.ribbon,.diagram details{display:none}main{border:0;padding:0}h2{break-before:page}h3{break-after:avoid}.table-scroll{overflow:visible;border:0}table{font-size:9pt;min-width:0}th,td{padding:6px;min-width:0}thead{display:table-header-group}tr{break-inside:avoid}pre{white-space:pre-wrap;color:#111;background:#f2f2f2}a{color:inherit}.diagram{break-inside:avoid}.diagram figcaption a{display:none}}
'''
    html_text = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Holiday Booking · 完整开发技术文档</title><style>{css}</style></head><body>
<div class="layout"><aside><div class="brand">Holiday Booking<br>开发技术文档</div>
<div class="small">v1.0 · 2026-10-21<br>离线阅读 · Ctrl+F 搜索正文</div>
{md.toc}<hr><div class="small"><a href="{SOURCE.name}">Markdown 原稿</a><br>
<a href="requirements-traceability.csv">需求追踪表</a><br><a href="test-cases.csv">基础测试计划</a><br>
<a href="change-design-checks.csv">变更设计验证表</a></div></aside>
<main><div class="ribbon">开发前设计基线 · 需求 / 架构 / 数据 / 接口 / 验收</div>{body}
<div class="foot">由同目录 Markdown 原稿生成。本文为设计资料，所有测试条目初始为未执行；图示不代表已经运行的系统。</div></main></div></body></html>'''
    (DOCS / 'Holiday-Booking-开发技术文档.html').write_text(html_text, encoding='utf-8')


def main() -> None:
    source = SOURCE.read_text(encoding='utf-8')
    diagrams = render_diagrams(source)
    write_csv('requirements-traceability.csv', table_rows(source, '## 3.', '## 4.'),
              ['负责人', '实现状态', '证据'], ['', '未实现／仅设计', ''])
    write_csv('test-cases.csv', table_rows(source, '### 15.2', '### 15.3'),
              ['前置条件与步骤', '实际结果', '状态', '证据路径', '缺陷ID', '执行人', '构建版本', '执行时间'],
              ['', '', '未执行', '', '', '', '', ''])
    change_rows = table_rows(source, '| 验证 ID', '## 17.')
    change_rows.insert(0, ['验证 ID', '推演场景', '设计必须满足'])
    write_csv('change-design-checks.csv', change_rows,
              ['评审结论', '状态', '评审人', '证据'], ['', '未执行', '', ''])
    build_html(source, diagrams)
    evidence = {'document_date': '2026-10-21', 'sources': []}
    for pdf in sorted(ROOT.glob('*.pdf')):
        evidence['sources'].append({
            'file': pdf.name, 'bytes': pdf.stat().st_size,
            'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(),
        })
    evidence['document_sha256'] = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    evidence['note'] = 'Checksums identify the read source files; no application tests were run.'
    (DOCS / 'source-manifest.json').write_text(
        json.dumps(evidence, indent=2, ensure_ascii=False), encoding='utf-8')
    print('Built offline HTML, 5 SVG diagrams, 3 CSV planning tables and source manifest.', flush=True)


if __name__ == '__main__':
    main()
