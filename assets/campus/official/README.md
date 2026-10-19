# 校方公开校园影像

查阅与下载日期：2026-10-04。原 JPG 文件保留原文件内容，前端通过 CSS 适配图框，来源链接展示在页面中。

| 文件 | 来源页面 | 原图地址 | 用途 |
| --- | --- | --- | --- |
| admissions-banner02.jpg | https://zsb.zzuit.edu.cn/ | https://zsb.zzuit.edu.cn/images/banner02.jpg | 校园全景：首屏轮播、登录与门户配图 |
| admissions-banner01.jpg | https://zsb.zzuit.edu.cn/ | https://zsb.zzuit.edu.cn/images/banner01.jpg | 师生学习场景：首屏轮播 |
| XMT_9742.jpg | https://www.zzuit.edu.cn/gyxy/xyfg.htm | https://www.zzuit.edu.cn/images/22/04/21/1jarylhk3e/XMT_9742.jpg | 春日花枝与建筑：首屏轮播 |
| XMT_9744.jpg | https://www.zzuit.edu.cn/gyxy/xyfg.htm | https://www.zzuit.edu.cn/images/22/04/21/1jarylhk3e/XMT_9744.jpg | 校园建筑配图 |

素材仅根据官方发布页面说明来源，不推断拍摄日期或科研项目背景。影像配置集中在 frontend/src/content/schoolMedia.ts。

## 2026-10-19 首页章节影像替换

首页三张轮播与六个有照片的章节分别使用九张不同照片。下面新增的六张来自校方公开页面，编码为 WebP，总计约 529 KiB；保留画面与原始像素尺寸。原图 URL、尺寸、字节数、SHA-256 和查阅日期见 [homepage-photos-2026-10-19.json](./homepage-photos-2026-10-19.json)。

| 文件 | 章节与画面 | 原发布页面 |
| --- | --- | --- |
| campus-lakeside.webp | 学校概况：湖面、草坪与校园建筑 | [校园风光](https://www.zzuit.edu.cn/gyxy/xyfg.htm) |
| engineering-practice.webp | 教育教学：工程设备操作与讲解 | [校园人文](https://www.zzuit.edu.cn/gyxy/xyrw.htm) |
| discipline-seminar.webp | 科学研究：2026年重点（培育）学科阶段性检查工作会 | [校方新闻，2026-09-29](https://www.zzuit.edu.cn/info/1016/8705.htm) |
| freshmen-ceremony.webp | 招生就业：2026级新生开学典礼上的国旗护卫队 | [校方新闻，2026-09-19](https://www.zzuit.edu.cn/info/1016/8686.htm) |
| international-agreement.webp | 合作交流：与俄罗斯南乌拉尔国立人文师范大学签署合作备忘录 | [校方新闻，2026-10-17](https://www.zzuit.edu.cn/info/1016/8716.htm) |
| welcome-performance.webp | 校园生活：2026年迎新文艺晚会舞蹈演出 | [校方新闻，2026-09-20](https://www.zzuit.edu.cn/info/1016/8687.htm) |

网页图注保留各自的来源链接；校园生活背景图增加可访问的图片说明和出处。概况图片的裁切位置由素材配置控制。文件的 WebP 转码未改变照片内容，也未扩大原图尺寸。

上一级目录原有 hero-* 生成图保留为历史设计资料，当前官网与登录页已采用以上真实影像。Three.js 场景仍为演示布局，摄影更换不意味着 3D 建筑已校准。
