pub struct Formatter;

impl Formatter {
    pub fn format_github_star(stars: u64) -> String {
        if stars >= 1000 {
            let value = stars as f64 / 1000.0;
            let rounded = format!("{value:.1}");
            return format!("{}k", rounded.trim_end_matches(".0"));
        }
        stars.to_string()
    }
}
