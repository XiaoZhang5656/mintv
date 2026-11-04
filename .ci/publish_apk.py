"""把构建产物回写进仓库 public/apk/。

不用 git push：CI runner 到 github.com 的 443 常被掐。API 走另一条路由。
落盘后 raw.githubusercontent.com / ghproxy 都能直链下载。
"""
import base64
import glob
import json
import os
import time
import urllib.error
import urllib.request

TOKEN = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
REPO = os.environ['GH_REPO']
PATH = 'public/apk/tvui-java-debug.apk'
API = 'https://api.github.com/repos/%s/contents/%s' % (REPO, PATH)


def call(url, method='GET', body=None, tries=6, ok404=False):
    data = json.dumps(body).encode() if body is not None else None
    last = ''
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, method=method, headers={
                'Authorization': 'Bearer ' + TOKEN,
                'Accept': 'application/vnd.github+json',
                'Content-Type': 'application/json',
                'Connection': 'close',
            })
            with urllib.request.urlopen(req, timeout=90) as r:
                raw = r.read().decode()
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            if e.code == 404 and ok404:
                return None
            last = 'HTTP %s %s' % (e.code, e.read()[:300])
            if e.code in (401, 403):
                break            # 权限问题，重试没用
        except Exception as e:
            last = str(e)
        time.sleep(2 + i * 3)
    raise SystemExit('publish failed: %s' % last)


def read_version_code(apk_path):
    """从 build.gradle 读 versionCode —— 比解析 AXML 简单可靠。
    CI 里 gradle 刚从这份文件构建，两者必然一致。"""
    import re
    txt = open('app/build.gradle', encoding='utf-8').read()
    m = re.search(r'versionCode\s+(\d+)', txt)
    return m.group(1) if m else '?'


def read_version_name():
    """从 public/version.json 读 versionName，读不到返回空串。"""
    try:
        with open('public/version.json', encoding='utf-8') as f:
            return str(json.load(f).get('versionName') or '')
    except Exception:
        return ''

def target_name(apk_path):
    """文件名带上版本号：mintv-2.51.0-arm64-v8a.apk

    同时保留一份不带版本号的固定名，老链接继续可用。
    返回 (带版本号路径, 固定名路径)。
    """
    base = os.path.basename(apk_path)              # app-arm64-v8a-debug.apk
    abi = base.replace('app-', '').replace('-debug.apk', '')
    ver = read_version_name()
    fixed = 'public/apk/' + base
    if not ver:
        return fixed, None
    versioned = 'public/apk/mintv-%s-%s.apk' % (ver, abi)
    return versioned, fixed

def publish(apk_path, repo_path):
    """用 Git Data API 上传，不用 Contents API。

    Contents API 单文件实际上限约 50MB（base64 后翻 4/3），
    APK 到 47MB 就会被 422 拒掉：
      "the file is too large to be processed"

    Git Data API 的 blob 支持到 100MB，而且不需要 base64 之外的额外膨胀。
    """
    data = open(apk_path, 'rb').read()
    # version.json 不是 apk，读 versionCode 会抛异常
    vc = read_version_code(apk_path) if apk_path.endswith('.apk') else '-' 
    print('publish', apk_path, '->', repo_path, len(data), 'bytes versionCode=', vc)

    repo = 'https://api.github.com/repos/%s' % REPO

    # 1) 当前 head
    head = call(repo + '/git/ref/heads/main')
    parent = head['object']['sha']

    # 2) 传 blob
    blob = call(repo + '/git/blobs', 'POST', {
        'content': base64.b64encode(data).decode(),
        'encoding': 'base64',
    })
    print('  blob', blob['sha'][:10])

    # 3) 在父 commit 的树上加这一个文件
    base = call(repo + '/git/commits/' + parent)
    tree = call(repo + '/git/trees', 'POST', {
        'base_tree': base['tree']['sha'],
        'tree': [{'path': repo_path, 'mode': '100644',
                  'type': 'blob', 'sha': blob['sha']}],
    })

    # 4) 提交并移动分支
    commit = call(repo + '/git/commits', 'POST', {
        'message': 'ci: publish debug apk [skip ci]',
        'tree': tree['sha'],
        'parents': [parent],
    })
    call(repo + '/git/refs/heads/main', 'PATCH', {'sha': commit['sha']})
    print('  commit', commit['sha'][:10])

def main():
    apks = sorted(glob.glob('app/build/outputs/apk/debug/*.apk'))
    if not apks:
        raise SystemExit('no apk found')
    for a in apks:
        versioned, fixed = target_name(a)
        publish(a, versioned)
        # 同时发一份固定名，老的下载链接不会断
        if fixed and fixed != versioned:
            publish(a, fixed)
    # version.json 也要发：git push 失败时它没有上去，
    # 客户端靠它判断有没有新版本
    vj = 'public/version.json'
    if os.path.exists(vj):
        publish(vj, vj)


if __name__ == '__main__':
    main()
