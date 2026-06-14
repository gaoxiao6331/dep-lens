# 跨平台解析逻辑一致性

## 概述

为了确保 Rust、WASM 和 Kotlin 三种实现的 Go 依赖解析逻辑保持一致，我们遵循以下规范：

## 核心解析规则（所有平台必须保持一致）

### 1. GitHub 路径解析
- 支持格式：
  - Go 源码导入：`"github.com/owner/repo/subpkg"`
  - go.mod 模块：`github.com/owner/repo v1.2.3`
  
- 解析逻辑：
  - 提取 `owner` 和 `repo` 部分
  - 在 go.mod 中，跳过包含 `// indirect` 的行
  - 位置计算：
    - 对于 Go 导入：在结束引号后
    - 对于 go.mod：在整行结尾

### 2. GitHub 路径正则表达式
所有平台使用相同的正则匹配规则：
- 匹配 `github.com/owner/repo`
- owner 和 repo 可包含字符：`A-Za-z0-9_.-`

## 各平台实现

### 1. Rust 实现（参考标准）
位置：`lib/src/go.rs`
- 使用 `regex` crate
- `parse_go_dependencies()` 函数作为标准实现

### 2. WASM 实现
位置：通过 FFI 调用 Rust 相同函数
- 保持与 Rust 完全一致

### 3. Kotlin 实现
位置：`jetbrains/src/main/kotlin/deplens/lang/go/GoDepLensInlayProvider.kt`
- 使用 `Regex` 类
- 保持与 Rust 逻辑相同
