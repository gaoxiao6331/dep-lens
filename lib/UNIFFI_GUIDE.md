# Dep-Lens 跨平台库使用指南

## 概述

这个库现在可以支持三种平台使用：

1. **Rust 直接调用** - 用于 Zed 插件等 Rust 项目
2. **WASM 调用** - 用于 VS Code 插件
3. **Kotlin 调用** - 用于 JetBrains 插件（通过 FFI 或保持逻辑一致）

## 项目结构

```
dep-lens/
├── lib/              # Rust 核心库
│   ├── src/
│   │   ├── lib.rs   # 主入口
│   │   ├── go.rs    # Go 依赖分析核心逻辑
│   │   ├── jni.rs   # JNI 绑定（可选）
│   │   └── dep_lens.udl  # UniFFI 定义（可选）
│   └── Cargo.toml
├── jetbrains/       # JetBrains IDE 插件
├── vscode/         # VS Code 插件
└── zed/            # Zed 编辑器插件
```

## 使用方法

### 1. Rust 直接调用

添加依赖到你的 `Cargo.toml`：
```toml
[dependencies]
dep-lens-lib = { path = "../lib" }
```

使用示例：
```rust
use dep_lens_lib::{parse_go_dependencies, GoDependency};

fn main() {
    let text = r#"import "github.com/owner/repo/subpkg""#;
    let dependencies = parse_go_dependencies(text, "main.go", "go", 0, 100);
    
    for dep in dependencies {
        println!("Found: {}/{} at line {}", dep.owner, dep.repo, dep.line);
    }
}
```

### 2. WASM 调用

构建 WASM：
```bash
cd lib
cargo build --target wasm32-unknown-unknown --release
```

生成的 WASM 文件位于：
`target/wasm32-unknown-unknown/release/dep_lens_lib.wasm`

### 3. Kotlin 调用（JetBrains 插件）

对于 JetBrains 插件，我们采用了一个**更实用的方案**：
- 保持现有的 Kotlin 实现，但确保逻辑与 Rust 库完全一致
- 使用相同的正则表达式和解析规则

参考实现：`jetbrains/src/main/kotlin/deplens/lang/go/GoDepLensInlayProvider.kt`

## 解析逻辑一致性

为了确保所有平台的行为一致，我们统一了以下规则：

1. **GitHub 路径正则表达式**：`github\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)`
2. **Go 导入匹配**：`"github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/[^\"]*)?"`
3. **间接依赖处理**：跳过 go.mod 中包含 `// indirect` 的行
4. **位置计算**：
   - Go 导入：在结束引号后
   - go.mod：在整行结尾

## 测试

运行测试：
```bash
cd lib
cargo test
```

## 已完成的工作

✅ **Rust 库重构** - 支持跨平台使用
✅ **WASM 支持** - 完整保留
✅ **Kotlin 实现一致性** - 与 Rust 逻辑保持一致
✅ **三种平台兼容性** - Rust/WASM/Kotlin 都可以正常工作
