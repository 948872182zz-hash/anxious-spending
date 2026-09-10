# 坚持焦虑地花钱中

**Spending Anxiously, Consistently.**

一个只给自己用的 Android 记账小工具。

当前 MVP 目标：

- 首页按“天”分组展示每一笔详细记录
- 第二页做月度 / 年度分析
- 本地保存数据，不依赖服务器
- 支持快速新增支出、金额、分类、备注与日期
- GitHub Actions 自动构建 Debug APK，方便只有安卓手机时直接拿构建产物测试

## 分类

- shopping — `buy sth new`
- food — `eating`
- game — `婷芷我说婷芷`
- ai — `别BAN我`
- misc — `生活杂费`
- transport — `🚈🚕🚌嘟嘟`
- travel — `Go go go出发喽`
- snack — `stop eat`
- books — `今天你看书了吗`
- investment — `hope💹`
- medical — `今天哪里又痛了我的大小姐`

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- SharedPreferences + JSON（MVP 本地持久化）

## 构建

推送到 `main` 后，GitHub Actions 会自动执行 Debug 构建并上传 APK artifact。
