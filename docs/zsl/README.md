# Darkbag 真 ZSL — 实施状态表

- **设计文档**：[`../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md`](../TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md)（v2.1）
- **分支**：`feat/true-zsl-capture`（基线 `refactor/datastore-and-arch-hardening` @ `cdb8d52e`）
- **流程**：Implementer 实现 → Reviewer 独立审计（`APPROVED` / `REVISE_REQUIRED`）→ Orchestrator 原子提交 + 推送

## 阶段状态

| 阶段 | 状态 | 提交 | 证据包 | Reviewer |
|---|---|---|---|---|
| S0 地基与遥测 | 进行中 | — | `evidence/S0_*.md` | 待审计 |
| S1 探测与实验开关（含 Bento UI） | 未开始 | — | — | — |
| **D1 决策门**（go / no-go） | 未开始 | — | — | — |
| S2 Z1 单帧 ZSL（里程碑 M1） | 未开始 | — | — | — |
| S3 Z2 低照 HDR+ 混合 | 未开始 | — | — | — |
| S4 Z3 全场景 + 取景增益（里程碑 M2） | 未开始 | — | — | — |

## 门禁记录

| 日期 | 阶段 | 单元测试 | Native 构建 | Reviewer 裁决 | 备注 |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

## 约定

- 每阶段完成后更新本表，并在 `evidence/` 落地证据包（原始日志、测量表、请求转储、门禁表、未完成项）。
- 未通过 Reviewer 的阶段不得提交；提交信息采用 `feat(zsl)` / `refactor(zsl)` / `test(zsl)` / `docs(zsl)` 前缀。
