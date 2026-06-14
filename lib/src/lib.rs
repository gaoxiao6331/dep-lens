pub mod go;
pub mod jni;

// 重新导出核心功能供外部使用
pub use go::{parse_go_dependencies, GoDependency};
