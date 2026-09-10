# 坚持焦虑地花钱中

**Spending Anxiously, Consistently.**

一个只给自己用的 Android 记账小工具。

当前进入 **2.0 系列**：支出录入已经基本完成，开始加入收入记录，并逐步把“记账”升级成完整的收支账本。

当前目标：

- 首页按“天”分组展示每一笔详细记录
- 支持支出 / 收入两种记录类型
- 第二页做月度 / 年度分析
- 本地保存数据，不依赖服务器
- 支持金额、分类、备注、日期、支付来源与多币种记录
- 外币记录保存当时汇率及折算后的人民币金额
- 常用备注按分类统计，重复出现后自动形成标签，并按使用频率排序
- GitHub Actions 自动构建 Debug APK，方便只有安卓手机时直接拿构建产物测试

## 支出分类

- shopping — `BUY STH NEW`
- food — `EATING`
- game — `婷芷我说婷芷`
- ai — `别BAN我`
- misc — `生活杂费`
- transport — `🚈🚕🚌嘟嘟`
- travel — `GO GO GO出发喽`
- snack — `STOP EAT`
- books — `今天你看书了吗`
- investment — `HOPE💹`
- medical — `今天哪里又痛了我的大小姐`

## 收入分类

- salary — `钱来`
- red_packet — `意外收获`
- investment_income — `HOPE一直💹`
- aa_income — `没有惊喜的一笔钱`

## 已支持

- 支出 / 收入切换
- 首页按日期展示记录
- 记录编辑与删除
- CNY / USD / KRW / JPY 多币种
- 汇率缓存与联网刷新
- 记录原币金额与人民币折算金额
- 支付来源记录
- 分类级常用备注标签
- 手动日期输入，以及 ±1 天 / 今天快捷调整
- 月 / 年分析页基础版本

## 2.0 后续

- 收入分析
- 收支汇总与结余
- 环比 / 同比
- 自定义时间周期分析
- 支付来源结构分析
- 半年度总结海报
- 年度总结海报

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- SharedPreferences + JSON（当前本地持久化）

## 构建

推送到 `main` 后，GitHub Actions 会自动执行 Debug 构建并上传 APK artifact。
