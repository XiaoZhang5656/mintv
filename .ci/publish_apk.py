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


def target_name(apk_path):
    """把一个打包产物映射成仓库里的文件名。

    开启 ABI 分包后会同时产出 app-arm64-v8a-debug.apk 和
    app-armeabi-v7a-debug.apk，不能都叫 tvui-java-debug.apk 互相覆盖。
    """
    base = os.path.basename(apk_path)
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
        if abi in base:
            return 'mintv-%s.apk' % abi
    return 'tvui-java-debug.apk'


def publish(apk_path, repo_path):
    data = open(apk_path, 'rb').read()
    vc = read_version_code(apk_path)
    api = 'https://api.github.com/repos/%s/contents/%s' % (REPO, repo_path)
    print('publish', apk_path, '->', repo_path, len(data), 'bytes versionCode=', vc)
    cur = call(api, ok404=True)
    body = {
        'message': 'ci: publish debug apk [skip ci]',
        'content': base64.b64encode(data).decode(),
        'branch': 'main',
    }
    if cur and 'sha' in cur:
        body['sha'] = cur['sha']
    res = call(api, 'PUT', body)
    print('  commit', (res.get('commit') or {}).get('sha', '')[:10])


def main():
    apks = sorted(glob.glob('app/build/outputs/apk/debug/*.apk'))
    if not apks:
        raise SystemExit('no apk found')
    for a in apks:
        publish(a, target_name(a))


if __name__ == '__main__':
    main()
