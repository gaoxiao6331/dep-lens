import * as vscode from "vscode";

export class TsImportResolver {
  static isImport(element: string): boolean {
    // Check if the line is a TypeScript/JavaScript import statement
    const importPatterns = [
      /^\s*import\s+.*?\s+from\s+['"]([^'"]+)['"]/,
      /^\s*import\s+['"]([^'"]+)['"]/,
      /^\s*require\s*\(\s*['"]([^'"]+)['"]\s*\)/,
      /^\s*export\s+.*?\s+from\s+['"]([^'"]+)['"]/
    ];

    return importPatterns.some(pattern => pattern.test(element));
  }

  static getDepName(line: string): string | null {
    // Extract dependency name from import statement
    const patterns = [
      /^\s*import\s+.*?\s+from\s+['"]([^'"]+)['"]/,
      /^\s*import\s+['"]([^'"]+)['"]/,
      /^\s*require\s*\(\s*['"]([^'"]+)['"]\s*\)/,
      /^\s*export\s+.*?\s+from\s+['"]([^'"]+)['"]/
    ];

    for (const pattern of patterns) {
      const match = line.match(pattern);
      if (match) {
        return match[1];
      }
    }
    return null;
  }

  static isLocalImport(dep: string): boolean {
    // Check if import is local (relative path or absolute path)
    return dep.startsWith('.') || dep.startsWith('/') || dep.startsWith('@/');
  }

  static getPkgName(dep: string): string {
    // Extract package name from dependency (handles scoped packages)
    if (dep.startsWith('@')) {
      // Scoped package like @angular/core
      const parts = dep.split('/');
      return parts.slice(0, 2).join('/');
    } else {
      // Regular package like lodash
      return dep.split('/')[0];
    }
  }
}