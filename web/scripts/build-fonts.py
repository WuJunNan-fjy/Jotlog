#!/usr/bin/env python3
"""生成自托管字体资产。

产物（都在 public/fonts/ 下，随 dist 一起部署）：
  fonts.css                       唯一的 @font-face 清单，index.html 直接 link
  sans/files/*.woff2               思源黑体（Noto Sans SC）variable，按 unicode-range 分片
  serif/noto-serif-sc-400.woff2    思源宋体（Noto Serif SC）400，界面文案子集

为什么黑体用分片、宋体用单文件：
  黑体是正文，字符集不可预测，用官方的 unicode-range 分片让浏览器按需下载——
  典型一屏只命中 2~6 片，约 100~300KB，且 wght 100-900 全部是真字重（不是合成粗体）。
  宋体只用在日期分隔这类短装饰文本上，字符完全可控，所以打成单文件一次性下载。

为什么不要用这份脚本以外的字体文件：
  分片和 unicode-range 是配套的，换版本必须整包重跑，否则 CSS 里的码位范围和
  实际字形对不上，会出现静默缺字。

依赖：python -m pip install fonttools brotli
用法：python scripts/build-fonts.py
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLIC_FONTS = ROOT / 'public' / 'fonts'
CACHE = ROOT / '.fontcache'

SANS_PKG = '@fontsource-variable/noto-sans-sc@5.3.0'
SERIF_PKG = '@fontsource/noto-serif-sc@5.3.0'
SANS_FAMILY = 'Noto Sans SC Variable'
SERIF_FAMILY = 'Noto Serif SC'

# 界面文案之外固定要带的字符。
# 宋体只装饰短文本，缺字会静默回落到系统宋体——13px 大字距下看不出来，
# 但日期、数字、常用标点这些高频字符必须自己有，否则一眼就能看出字形打架。
EXTRA_CHARS = (
    '年月日时分秒周星期今天昨前天明早晨午晚夜凌晨上下午点刻'
    '〇零一二三四五六七八九十百千万亿两'
    '①②③④⑤⑥⑦⑧⑨⑩'
    '℃℉№㎡㎏℡★☆○●◆■□▲△▼▽→←↑↓↔×÷±≈≤≥∞§¶©®™'
    '　、。，．；：？！…—～《》〈〉「」『』（）【】〖〗'
)

# 拉丁与通用符号：范围比列举更省事，且这些字形本身很小
EXTRA_RANGES = [
    (0x20, 0x7E),      # ASCII
    (0xA0, 0xFF),      # Latin-1 补充（¥ © ° × ÷）
    (0x2000, 0x206F),  # 通用标点（— ‘ ’ “ ” … ‰）
    (0x20A0, 0x20BF),  # 货币符号
    (0x2100, 0x214F),  # 字母符号（℃ ℡ №）
    (0x2190, 0x21FF),  # 箭头
    (0x2200, 0x22FF),  # 数学运算
    (0x2460, 0x24FF),  # 带圈数字
    (0x25A0, 0x25FF),  # 几何图形
    (0x3000, 0x303F),  # CJK 标点
    (0xFF00, 0xFFEF),  # 全角半角
]


def log(msg: str) -> None:
    print(f'  {msg}', flush=True)


def step(msg: str) -> None:
    print(f'\n==> {msg}', flush=True)


def fetch(pkg: str) -> Path:
    """npm pack 拉包并解压，返回解压目录。同一版本只拉一次。"""
    name = pkg.split('/')[-1]                     # noto-sans-sc@5.3.0
    base = name.split('@')[0]                     # noto-sans-sc
    dest = CACHE / base
    if (dest / 'package').is_dir():
        log(f'缓存命中 {base}')
        return dest

    CACHE.mkdir(parents=True, exist_ok=True)
    tgz = CACHE / f'{name.replace("@", "-")}.tgz'
    if not tgz.exists():
        log(f'npm pack {pkg}')
        subprocess.run(
            ['npm', 'pack', pkg, '--silent'],
            cwd=CACHE, check=True,
            stdout=subprocess.DEVNULL,
        )
        # npm pack 的产出名由仓库决定，兜一层 glob
        found = sorted(CACHE.glob('*.tgz'))
        if not found:
            raise SystemExit('npm pack 没有产出 tgz')
        found[-1].rename(tgz)

    log(f'解压 {tgz.name}')
    with tarfile.open(tgz) as tf:
        tf.extractall(dest)
    return dest


def scan_ui_chars() -> set[int]:
    """扫描界面源码里出现过的 CJK 字符。

    宋体不是给用户内容用的，是给界面文案用的。所以按实际文案取字符集就够了——
    这份集合小， subset 后只有一百来 KB。改了界面文案记得重跑本脚本。
    """
    pat = re.compile(r'[⺀-鿿　-〿＀-￯]')
    chars: set[int] = set()
    paths = []
    for ext in ('vue', 'ts', 'js', 'html', 'css', 'json'):
        paths += glob.glob(str(ROOT / 'src' / '**' / f'*.{ext}'), recursive=True)
    paths.append(str(ROOT / 'index.html'))
    paths += glob.glob(str(ROOT / 'public' / '*.webmanifest'))
    for p in paths:
        try:
            chars |= {ord(c) for c in pat.findall(Path(p).read_text(encoding='utf-8'))}
        except OSError:
            continue
    return chars


def build_serif(src_dir: Path) -> None:
    from fontTools.merge import Merger

    out_dir = PUBLIC_FONTS / 'serif'
    out_dir.mkdir(parents=True, exist_ok=True)
    woff2 = out_dir / 'noto-serif-sc-400.woff2'

    parts = sorted((src_dir / 'package' / 'files').glob('*-400-normal.woff2'))
    if not parts:
        raise SystemExit('宋体包里找不到 400 字重分片')
    step(f'合并宋体 400（{len(parts)} 个分片）')
    merged = Merger().merge([str(p) for p in parts])
    merged.flavor = None

    tmp_ttf = Path(tempfile.mkdtemp()) / 'serif-400.ttf'
    merged.save(str(tmp_ttf))
    log(f'全量 {tmp_ttf.stat().st_size / 1048576:.1f} MB')

    chars = scan_ui_chars() | {ord(c) for c in EXTRA_CHARS}
    for lo, hi in EXTRA_RANGES:
        chars |= set(range(lo, hi + 1))
    log(f'字符集 {len(chars)} 码位（界面文案扫描 + 固定补充）')

    step('子集化并压成 woff2')
    # 字符集可能很长，写文件传参比塞命令行稳（Windows 命令行有长度上限）
    unicodes_file = tmp_ttf.with_suffix('.txt')
    unicodes_file.write_text(','.join(f'U+{c:04X}' for c in sorted(chars)), encoding='utf-8')

    # 只留下排版真正用得上的 feature。全量 GSUB/GPOS 对宋体这种装饰字体是纯浪费。
    cmd = [
        sys.executable, '-m', 'fontTools.subset', str(tmp_ttf),
        f'--unicodes-file={unicodes_file}',
        '--layout-features=kern,liga,clig,calt,tnum,lnum,onum,pnum',
        '--no-hinting',           # WebFont 不需要 hinting，省体积
        '--drop-tables=DSIG',
        '--flavor=woff2',
        f'--output-file={woff2}',
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    log(f'产出 {woff2.name} {woff2.stat().st_size / 1024:.0f} KB')

    lic = src_dir / 'package' / 'LICENSE'
    if lic.exists():
        shutil.copy(lic, out_dir / 'LICENSE')


def build_sans(src_dir: Path) -> None:
    """黑体：原样搬官方分片，只改 CSS 里的 url 前缀。"""
    step('拷贝思源黑体 variable 分片')
    files_src = src_dir / 'package' / 'files'
    files_dst = PUBLIC_FONTS / 'sans' / 'files'
    if files_dst.exists():
        shutil.rmtree(files_dst)
    files_dst.mkdir(parents=True, exist_ok=True)

    n = 0
    for f in sorted(files_src.glob('*-wght-normal.woff2')):
        shutil.copy(f, files_dst / f.name)
        n += 1
    log(f'{n} 个分片')

    lic = src_dir / 'package' / 'LICENSE'
    if lic.exists():
        shutil.copy(lic, PUBLIC_FONTS / 'sans' / 'LICENSE')

    css = (src_dir / 'package' / 'wght.css').read_text(encoding='utf-8')
    # url 从 ./files/x.woff2 改成 ./sans/files/x.woff2，因为 fonts.css 在上一级
    css = css.replace('url(./files/', 'url(./sans/files/')
    return css


def write_fonts_css(sans_css: str) -> None:
    step('生成 fonts.css')
    serif = """/* 思源宋体（Noto Serif SC）400 —— 界面文案子集，由 scripts/build-fonts.py 生成。
   只给日期分隔、小标题这类短装饰文本用；界面文案改了要重跑脚本。
   子集外的字会回落到系统宋体，不会出豆腐块。 */
@font-face {
  font-family: 'Noto Serif SC';
  font-style: normal;
  font-display: swap;
  font-weight: 400;
  src: url(./serif/noto-serif-sc-400.woff2) format('woff2');
}
"""
    css = (
        '/* 由 scripts/build-fonts.py 生成，不要手改。\n'
        '   思源黑体 / 思源宋体，SIL Open Font License 1.1，\n'
        '   许可证见 sans/LICENSE 与 serif/LICENSE。 */\n\n'
        + sans_css
        + '\n'
        + serif
    )
    out = PUBLIC_FONTS / 'fonts.css'
    out.write_text(css, encoding='utf-8')
    log(f'{out.name} {out.stat().st_size / 1024:.0f} KB')


def main() -> None:
    os.chdir(ROOT)
    try:
        import fontTools  # noqa: F401
        import brotli  # noqa: F401
    except ImportError:
        raise SystemExit(
            '缺依赖。先装：\n'
            f'  {sys.executable} -m pip install fonttools brotli'
        )

    PUBLIC_FONTS.mkdir(parents=True, exist_ok=True)
    sans_dir = fetch(SANS_PKG)
    serif_dir = fetch(SERIF_PKG)
    sans_css = build_sans(sans_dir)
    build_serif(serif_dir)
    write_fonts_css(sans_css)
    step('完成')


if __name__ == '__main__':
    main()
