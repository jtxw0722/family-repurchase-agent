# Agent 边界

本项目不是独立 Agent Runtime，而是 Agent Capability。

- OpenClaw / Codex / Claude Code：负责自然语言理解、规划、工具选择；
- 本项目：负责确定性工具能力和本地状态；
- Skill / CLAUDE.md：负责规则约束；
- SQLite：负责事实存储。

Agent 不应直接猜测价格结论。价格判断应以 `compare-price` 工具输出为准。
