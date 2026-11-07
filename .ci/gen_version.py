#!/usr/bin/env python3
"""从 git log 生成 version.json，推到产物仓库给客户端做「检测更新」。

为什么要自动生成：手写 changelog 迟早会忘。commit message 本来就是
按「fix: v0.3.84 (280) — 一句话标题」写的，直接抽出来就够用了。

输出格式（客户端按这个解析）：

    {
      "versionCode": 280,
      "versionName": "0.3.84",
      "date": "2026-11-07 19:36",
      "changes": ["这一版的要点", ...],
      "history": [ {"version": "...", "date": "...", "changes": [...]}, ... ],
      "apk": "https://raw.githubusercontent.com/.../app-armeabi-v7a-debug.apk"
    }
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sh(args):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True).stdout


def read_version():
    """从 app/build.gradle 读 versionCode / versionName。"""
    text = open(os.path.join(ROOT, 'app/build.gradle'), encoding='utf-8').read()
    vc = re.search(r'versionCode\s+(\d+)', text)
    vn = re.search(r'versionName\s+"([^"]+)"', text)
    return (int(vc.group(1)) if vc else 0,
            vn.group(1) if vn else '0.0.0')


def parse_commit(msg):
    """'fix: v0.3.84 (280) — 标题\\n\\n正文' → (version, 标题, [要点])"""
    lines = [l.rstrip() for l in msg.split('\n')]
    if not lines:
        return None
    head = lines[0]
    m = re.search(r'v(\d+\.\d+\.\d+)', head)
    if not m:
        return None
    version = m.group(1)
    title = re.split(r'[—-]\s*', head, maxsplit=1)
    title = title[1].strip() if len(title) > 1 else head

    # 要点 = 标题 + 正文里**顶格**的列表项。
    #
    # 只看顶格是有原因的：commit 正文里缩进的行通常是「代码片段说明」
    # 或者「分析过程的编号」，抓进来会变成
    #   「1. settings.json 里 proxy 变成 \"\"      ✅ 确实存了」
    # 这种内部细节，不该出现在更新日志里。
    bullets = [title.strip()]
    for ln in lines[1:]:
        if not ln.strip():
            continue
        if ln != ln.lstrip():
            continue                       # 缩进 → 跳过
        t = ln.strip()
        if t.startswith('- ') or t.startswith('* '):
            t = re.sub(r'^[-*]+\s*', '', t).strip()
            if t and len(t) < 120 and t not in bullets:
                bullets.append(t)
        if len(bullets) >= 6:
            break
    return version, title.strip(), bullets


def main():
    vc, vn = read_version()

    # 取最近 40 个 commit，过滤出带版本号的
    raw = sh(['git', 'log', '--pretty=format:%ad%x00%B%x00==END==',
              '--date=format:%Y-%m-%d %H:%M', '-n', '40'])
    blocks = [b for b in raw.split('==END==') if b.strip()]

    history = []
    seen = set()
    for b in blocks:
        parts = b.split('\x00', 1)
        if len(parts) != 2:
            continue
        date, msg = parts[0].strip(), parts[1]
        if 'ci:' in msg.split('\n')[0].lower():
            continue        # CI 自己的提交不算
        parsed = parse_commit(msg)
        if not parsed:
            continue
        version, title, bullets = parsed
        if version in seen:
            continue
        seen.add(version)
        history.append({'version': version, 'date': date, 'changes': bullets})
        if len(history) >= 8:
            break

    out = {
        'versionCode': vc,
        'versionName': vn,
        'date': history[0]['date'] if history else '',
        'changes': history[0]['changes'] if history else [],
        'history': history,
        'apk': ('https://raw.githubusercontent.com/XiaoZhang5656/mintv-apk/main/'
                'app-armeabi-v7a-debug.apk'),
    }

    path = os.path.join(ROOT, 'public', 'version.json')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print('已生成', path)
    print(json.dumps(out, ensure_ascii=False, indent=2)[:600])


if __name__ == '__main__':
    sys.exit(main())
