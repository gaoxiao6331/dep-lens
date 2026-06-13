use std::sync::OnceLock;

use crate::common::consts::NAME;

pub type LogLevel = &'static str;

pub struct Logger {
    level: LogLevel,
}

impl Logger {
    fn new() -> Self {
        let level = match std::env::var("DEP_LENS_LOG_LEVEL").ok().as_deref() {
            Some("debug") => "debug",
            Some("warn") => "warn",
            Some("error") => "error",
            _ => "info",
        };
        Self { level }
    }

    pub fn debug(&self, message: &str) {
        self.write("debug", message);
    }

    pub fn info(&self, message: &str) {
        self.write("info", message);
    }

    pub fn warn(&self, message: &str) {
        self.write("warn", message);
    }

    pub fn error(&self, message: &str) {
        self.write("error", message);
    }

    pub fn level(&self) -> LogLevel {
        self.level
    }

    fn write(&self, level: LogLevel, message: &str) {
        if !self.should_log(level) {
            return;
        }
        eprintln!("[{}] [{}] {}", NAME, level.to_uppercase(), message);
    }

    fn should_log(&self, level: LogLevel) -> bool {
        priority(level) >= priority(self.level)
    }
}

fn priority(level: LogLevel) -> u8 {
    match level {
        "debug" => 0,
        "info" => 1,
        "warn" => 2,
        "error" => 3,
        _ => 1,
    }
}

pub fn logger() -> &'static Logger {
    static LOGGER: OnceLock<Logger> = OnceLock::new();
    LOGGER.get_or_init(Logger::new)
}
