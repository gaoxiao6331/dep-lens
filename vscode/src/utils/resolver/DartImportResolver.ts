export class DartImportResolver {
  static isImport(line: string): boolean {
    return /^\s*import\s+['"]([^'"]+)['"]/.test(line);
  }

  static getDepName(line: string): string | null {
    const match = line.match(/^\s*import\s+['"]([^'"]+)['"]/);
    return match?.[1] ?? null;
  }

  static isLocalImport(dep: string): boolean {
    return dep.startsWith(".") || dep.startsWith("/") || dep.startsWith("dart:");
  }

  static isPackageImport(dep: string): boolean {
    return dep.startsWith("package:");
  }

  static getPkgName(dep: string): string {
    const normalized = dep.startsWith("package:") ? dep.slice("package:".length) : dep;
    return normalized.split("/")[0];
  }
}
