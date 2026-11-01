"""把构建出来的 APK 通过 GitHub Contents API 回写进仓库。

不用 `git push`：CI runner 到 github.com 的 443 经常被掐断，
API 走的是另一条路由，稳定得多。落进 public/apk/ 之后，
raw.githubusercontent.com / ghproxy 都能直链下载。
"""
import base64
import glob
import json
import os
import time
import urllib.error
import urllib.request

TOKEN = os.environ['GH_TOKEN']
REPO = os.environ['GH_REPO']
PATH = 'public/apk/tvui-java-debug.apk'
API = 'https://api.github.com/repos/%s/contents/%s' % (REPO, PATH)


def call(url, method='GET', body=None, tries=5):
    data = json.dumps(body).encode() if body is not None else None
    last = None
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
            # 404 = 文件还不存在，正常
            if e.code == 404:
                return None
            last = 'HTTP %s %s' % (e.code, e.read()[:200])
        except Exception as e:          # 网络抖动，重试
            last = str(e)
        time.sleep(2 + i * 3)
    raise RuntimeError('api failed: %s' % last)


def main():
    apk = glob.glob('app/build/outputs/apk/debug/*.apk')[0]
    data = open(apk, 'rb').read()
    print('apk size', len(data))

    cur = call(API)
    body = {
        'message': 'ci: publish debug apk [skip ci]',
        'content': base64.b64encode(data).decode(),
        'branch': 'main',
    }
    if cur and 'sha' in cur:
        body['sha'] = cur['sha']
    res = call(API, 'PUT', body)
    print('published', res.get('commit', {}).get('sha', '')[:10])


if __name__ == '__main__':
    main()
