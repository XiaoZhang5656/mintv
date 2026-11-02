"""把 app/build.gradle 里的版本号写进 public/version.json。

App 的「检测更新」就是读这个文件比对 versionCode，
所以它必须每次构建后自动更新，不能靠手改。
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
gradle = os.path.join(ROOT, 'app', 'build.gradle')
out = os.path.join(ROOT, 'public', 'version.json')

txt = open(gradle, encoding='utf-8').read()
vc = int(re.search(r'versionCode\s+(\d+)', txt).group(1))
vn = re.search(r'versionName\s+"([^"]+)"', txt).group(1)

os.makedirs(os.path.dirname(out), exist_ok=True)
json.dump({'versionCode': vc, 'versionName': vn}, open(out, 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
print('version.json ->', vc, vn)
