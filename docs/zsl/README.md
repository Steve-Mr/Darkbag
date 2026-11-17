# Darkbag 真 ZSL — 实施状态表

- **设计文档**：[`../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md`](../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md)（v2.1）
- **分支**：`feat/true-zsl-capture`（基线 `refactor/datastore-and-arch-hardening` @ `cdb8d52e`）
- **流程**：Implementer 实现 → Reviewer 独立审计（`APPROVED` / `REVISE_REQUIRED`）→ Orchestrator 原子提交 + 推送

## 阶段状态

| 阶段 | 状态 | 提交 | 证据包 | Reviewer |
|---|---|---|---|---|
| S0 地基与遥测 | **完成** | 见 git log `refactor(zsl): S0 ...` | [`evidence/S0_20261116.md`](evidence/S0_20261116.md) | **APPROVED**（round 2） |
| S1-A 探测核心（无 UI） | **完成** | 见 git log `feat(zsl): S1-A ...` | [`evidence/S1A_20261116.md`](evidence/S1A_20261116.md) | **APPROVED**（7 MINOR 转 S1-B 修复） |
| S1-B1 适配器 + 触发链 | **完成** | 见 git log `feat(zsl): S1-B1 ...` | [`evidence/S1B1_20261116.md`](evidence/S1B1_20261116.md) | **APPROVED**（12 MINOR 转 S1-B2） |
| S1-B2 生产加固（R1/R2/R3/R4/R6/R10/R11/R12） | **完成** | 见 git log `fix(zsl): S1-B2 ...` | [`evidence/S1B2_20261116.md`](evidence/S1B2_20261116.md) | **APPROVED**（round 2） |
| S1-B3 Bento UI（设置托盘 + 探测 sheet + 字符串） | **完成** | 见 git log `feat(zsl): S1-B3 ...` | [`evidence/S1B3_20261116.md`](evidence/S1B3_20261116.md) | **APPROVED**（round 3） |
| **D1 决策门**（go / no-go） | 未开始 | — | — | — |
| S2 Z1 单帧 ZSL（里程碑 M1） | 未开始 | — | — | — |
| S3 Z2 低照 HDR+ 混合 | 未开始 | — | — | — |
| S4 Z3 全场景 + 取景增益（里程碑 M2） | 未开始 | — | — | — |

## 门禁记录

| 日期 | 阶段 | 单元测试 | Native 构建 | Reviewer 裁决 | 备注 |
|---|---|---|---|---|---|
| 2026-11-16 | S0 | 557/557 通过（+24） | 未触及 native | **APPROVED** | F1 行预算（CHS 433→427、Lens 443→443）修复后通过；`prepare()` 顺延 S2 并记录于设计文档 §3.11 R4 |
| 2026-11-16 | S1-A | 623/623 通过（+66） | 未触及 native | **APPROVED** | 阈值表/六路径恢复/报告容错均通过；7 条 MINOR（safeGet 过宽、restore 失败未收口、abort 非即时、正向 verdict 缺 ring、fps 夹取、15fps 标称、恒真断言）转 S1-B |
| 2026-11-17 | S1-B3 | 719/719 通过（+29） | 未触及 native | **APPROVED** | B1（Details 空白按钮）修复；Aborted 独立 CANCELLED badge；三段进度；契约外提 `ZslProbeSheetContract`；证据包/设计文档数字对齐 |
| 2026-11-16 | S1-B2 | 690/690 通过（+35） | 未触及 native | **APPROVED** | F1（`getImageFormat` 实为 API19+，改为无条件真实格式校验）修复后通过；拆分适配器为 4 文件（282/43/62/28/51）；R1/R2/R4/R6/R10/R11/R12 闭环 |
| 2026-11-16 | S1-B1 | 655/655 通过（+32） | 未触及 native | **APPROVED** | 适配器/触发链代码级通过，真机证据列 §7；12 条 MINOR（restore 结果不可达、首帧 scratch 竞态、未校验 RAW 格式、latch 超时孤儿双流、W2 判据缺失、24→20 口径、Scope Lock、abort 无调用方、hydration、ReportStore 非原子、前置条件缺失）转 S1-B2/B3 |

## 约定

- 每阶段完成后更新本表，并在 `evidence/` 落地证据包（原始日志、测量表、请求转储、门禁表、未完成项）。
- 未通过 Reviewer 的阶段不得提交；提交信息采用 `feat(zsl)` / `refactor(zsl)` / `test(zsl)` / `docs(zsl)` 前缀。
