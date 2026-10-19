# PDF 中文字体

原样保留 `LXGWWenKai-Regular.ttf` 和完整 `OFL.txt`，仅在后端生成证明与场地借用凭证时使用，未加入前端下载。

来源：[LXGW WenKai](https://github.com/lxgw/LxgwWenKai)，获取于 2026-10-08；字体文件来自仓库 `fonts/TTF/LXGWWenKai-Regular.ttf`，字体按 SIL Open Font License 1.1 分发。SHA-256 见本轮验收记录。

PDFBox 按实际文字嵌入字体子集。字体不支持的字符以 `U+` 编码显示，不静默丢弃，也不使用系统字体依赖。
