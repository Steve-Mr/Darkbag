# Darkbag 真 ZSL — 实施状态表

- **设计文档**：[`../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md`](../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md)（v2.1）
- **分支**：`feat/true-zsl-capture`（基线 `refactor/datastore-and-arch-hardening` @ `cdb8d52e`）
- **流程**：Implementer 实现 → Reviewer 独立审计（`APPROVED` / `REVISE_REQUIRED`）→ Orchestrator 原子提交 + 推送

## 阶段状态

| 阶段 | 状态 | 提交 | 证据包 | Reviewer |
|---|---|---|---|---|
| S0 地基与遥测 | **完成** | 见 git log `refactor(zsl): S0 ...` | [`evidence/S0_20261116.md`](evidence/S0_20261116.md) | **APPROVED**（round 2） |
| S1-A 探测核心（无 UI） | **完成** | 见 git log `feat(zsl): S1-A ...` | [`evidence/S1A_20261116.md`](evidence/S1A_20261116.md) | **APPROVED**（7 MINOR 转 S1-B 修复） |
| S1-B 适配器/触发链/UI | 未开始 | — | — | — |
| **D1 决策门**（go / no-go） | 未开始 | — | — | — |
| S2 Z1 单帧 ZSL（里程碑 M1） | 未开始 | — | — | — |
| S3 Z2 低照 HDR+ 混合 | 未开始 | — | — | — |
| S4 Z3 全场景 + 取景增益（里程碑 M2） | 未开始 | — | — | — |

## 门禁记录

| 日期 | 阶段 | 单元测试 | Native 构建 | Reviewer 裁决 | 备注 |
|---|---|---|---|---|---|
| 2026-11-16 | S0 | 557/557 通过（+24） | 未触及 native | **APPROVED** | F1 行预算（CHS 433→427、Lens 443→443）修复后通过；`prepare()` 顺延 S2 并记录于设计文档 §3.11 R4 |
| 2026-11-16 | S1-A | 623/623 通过（+66） | 未触及 native | **APPROVED** | 阈值表/六路径恢复/报告容错均通过；7 条 MINOR（safeGet 过宽、restore 失败未收口、abort 非即时、正向 verdict 缺 ring、fps 夹取、15fps 标称、恒真断言）转 S1-B |

## 约定

- 每阶段完成后更新本表，并在 `evidence/` 落地证据包（原始日志、测量表、请求转储、门禁表、未完成项）。
- 未通过 Reviewer 的阶段不得提交；提交信息采用 `feat(zsl)` / `refactor(zsl)` / `test(zsl)` / `docs(zsl)` 前缀。
