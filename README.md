# DepLens

[中文](./docs/README_zh.md) ｜ English

DepLens is a plugin that **displays third-party dependency reliability inside the IDE**, helping developers quickly determine whether an imported dependency library is trustworthy when using AI-assisted coding.

When you use third-party dependencies in a project, DepLens automatically analyzes the repository information corresponding to the dependency and displays some key metrics in the IDE, such as:

* ⭐ GitHub Star count
* 🕒 Repository last update time

With this information, developers can more intuitively judge whether a dependency is:

* Maintained long-term
* Still active
* Possibly abandoned

Thus reducing the risk of using unreliable dependencies.

---

# Features

Currently implemented:

* Analyze **Go modules** in **JetBrains IDE**

* Automatically resolve the GitHub repository corresponding to dependencies

* **Inline display repository information in the IDE (Inlay Hint)**

    * GitHub Star count
    * Last update time

* Help developers quickly identify:

    * Niche dependencies
    * Long-unmaintained dependencies

Example:
![DepLens Go Modules Inlay Hint](./docs/img/dep-lens-gm-gomod.png)
![DepLens Go Modules Inlay Hint](./docs/img/dep-lens-gm-go.png)

---

# Roadmap

DepLens plans to gradually expand to more languages and IDEs.

| Language | JetBrains IDEs | VS Code    |
| -------- | -------------- | ---------- |
| Go       | ✅ Supported    | ✅ Supported  |
| JS/TS    | 🚧 WIP         | 📅 Planned |
| Java     | 📅 Planned     | 📅 Planned |
| Kotlin   | 📅 Planned     | 📅 Planned |
