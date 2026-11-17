#!/usr/bin/env python3
"""从 DeepSeek CDN 抓取 chat-demo 的构建产物，落到 android/app/src/main/assets/web/。

为什么抓构建产物而不是克隆源码：
  - https://github.com/deepseek-ai/chat-demo 需要 pnpm install + pnpm build（本机直连 GitHub 会超时）；
  - cdn.deepseek.com/chat-demo 上就是同一份代码的线上构建，抓下来即用，产物一致。
用法：
    python tools/fetch_assets.py            # 抓取 + 写 index.html（相对路径）
    python tools/fetch_assets.py --verify   # 只核对 chunk map 与本地文件是否齐
"""
import argparse
import os
import re
import sys
import urllib.error
import urllib.request

CDN = "https://cdn.deepseek.com/chat-demo/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "android", "app", "src", "main", "assets", "web")


def get(path: str) -> bytes:
    req = urllib.request.Request(CDN + path, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def write(rel: str, data: bytes) -> None:
    dst = os.path.join(WEB, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(data)


def fetch_all() -> None:
    index = get("index.html").decode("utf-8")
    # 页面里的 /chat-demo/static/... 绝对路径改成相对于文档根的相对路径
    index = index.replace('src="/chat-demo/', 'src="').replace('href="/chat-demo/', 'href="')
    write("index.html", index.encode("utf-8"))

    main_js = re.search(r'src="(static/js/main\.[0-9a-f]+\.js)"', index).group(1)
    main_css = re.search(r'href="(static/css/main\.[0-9a-f]+\.css)"', index).group(1)
    write(main_js, get(main_js))
    write(main_css, get(main_css))

    bundle = open(os.path.join(WEB, main_js), encoding="utf-8").read()
    seg = bundle[bundle.find("static/js/async/"):][:3000]
    chunks = re.findall(r'(\d+):"([0-9a-f]+)"', re.search(r"\(\{([^}]*)\}\)", seg).group(1))
    css_hash = re.search(r'static/css/async/"\+e\+"\.([0-9a-f]+)\.css', bundle).group(1)

    ok = 0
    for cid, h in chunks:
        rel = f"static/js/async/{cid}.{h}.js"
        write(rel, get(rel))
        ok += 1
        css = f"static/css/async/{cid}.{css_hash}.css"
        try:
            write(css, get(css))
            ok += 1
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
    print(f"done: {len(chunks)} chunks (js+css {ok} files) -> {WEB}")


def verify() -> int:
    index = open(os.path.join(WEB, "index.html"), encoding="utf-8").read()
    main_js = re.search(r'src="(static/js/main\.[0-9a-f]+\.js)"', index).group(1)
    bundle = open(os.path.join(WEB, main_js), encoding="utf-8").read()
    seg = bundle[bundle.find("static/js/async/"):][:3000]
    chunks = re.findall(r'(\d+):"([0-9a-f]+)"', re.search(r"\(\{([^}]*)\}\)", seg).group(1))
    missing = [f"{c}.{h}.js" for c, h in chunks
               if not os.path.exists(os.path.join(WEB, "static/js/async", f"{c}.{h}.js"))]
    print(f"chunks in map: {len(chunks)}, missing: {missing or 'none'}")
    return 1 if missing else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    a = ap.parse_args()
    sys.exit(verify() if a.verify else (fetch_all() or 0))
