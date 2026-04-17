import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { RepoKey } from "../service/RepoKey";

export class MavenRepoResolver {
  static async repoKeyFromGroupArtifact(
    groupId?: string | null,
    artifactId?: string | null,
    version?: string | null,
  ): Promise<RepoKey | null> {
    if (!groupId || !artifactId) {
      return null;
    }

    const resolvedVersion = await this.resolveLocalVersion(groupId, artifactId, version);
    if (!resolvedVersion) {
      return null;
    }

    return this.resolveRepoFromPom(groupId, artifactId, resolvedVersion, 0);
  }

  static async repoKeyFromResolvedPath(filePath?: string | null): Promise<RepoKey | null> {
    if (!filePath) {
      return null;
    }

    return (
      (await this.repoKeyFromMavenLocalPath(filePath)) ??
      (await this.repoKeyFromGradleCachePath(filePath))
    );
  }

  private static async repoKeyFromMavenLocalPath(filePath: string): Promise<RepoKey | null> {
    const marker = `${path.sep}.m2${path.sep}repository${path.sep}`;
    const markerIndex = filePath.indexOf(marker);
    if (markerIndex < 0) {
      return null;
    }

    const normalizedPath = filePath.replace(/\\/g, "/");
    const normalizedMarker = "/.m2/repository/";
    const normalizedMarkerIndex = normalizedPath.indexOf(normalizedMarker);
    if (normalizedMarkerIndex < 0) {
      return null;
    }

    const after = normalizedPath.slice(normalizedMarkerIndex + normalizedMarker.length).split("!")[0];
    const parts = after.split("/").filter(Boolean);
    if (parts.length < 4) {
      return null;
    }

    const artifactId = parts[parts.length - 3];
    const version = parts[parts.length - 2];
    const groupId = parts.slice(0, parts.length - 3).join(".");

    return this.repoKeyFromGroupArtifact(groupId, artifactId, version);
  }

  private static async repoKeyFromGradleCachePath(filePath: string): Promise<RepoKey | null> {
    const normalizedPath = filePath.replace(/\\/g, "/");
    const marker = "/.gradle/caches/modules-2/files-2.1/";
    const markerIndex = normalizedPath.indexOf(marker);
    if (markerIndex < 0) {
      return null;
    }

    const after = normalizedPath.slice(markerIndex + marker.length).split("!")[0];
    const parts = after.split("/").filter(Boolean);
    if (parts.length < 4) {
      return null;
    }

    const [groupId, artifactId, version, hash] = parts;
    const base = this.gradleCacheBase();
    const candidatePom = path.join(base, groupId, artifactId, version, hash, `${artifactId}-${version}.pom`);
    const pomFile = (await this.pathExists(candidatePom))
      ? candidatePom
      : await this.findPomInGradleCacheDir(base, groupId, artifactId, version);

    if (pomFile) {
      const content = await this.readText(pomFile);
      if (content) {
        const direct = this.extractGithubRepoKey(content);
        if (direct) {
          return direct;
        }

        const parent = this.extractParentCoords(content);
        if (parent && !this.hasTemplateVar(parent.groupId, parent.artifactId, parent.version)) {
          return this.resolveRepoFromPom(parent.groupId, parent.artifactId, parent.version, 0);
        }
      }
    }

    return this.repoKeyFromGroupArtifact(groupId, artifactId, version);
  }

  private static async resolveLocalVersion(
    groupId: string,
    artifactId: string,
    version?: string | null,
  ): Promise<string | null> {
    const cleaned = version?.trim();
    if (cleaned && !cleaned.includes("$")) {
      return cleaned;
    }

    const metadataFile = await this.localMetadataFile(groupId, artifactId);
    if (!metadataFile) {
      return null;
    }

    const metadata = await this.readText(metadataFile);
    if (!metadata) {
      return null;
    }

    const release = metadata.match(/<release>([^<]+)<\/release>/)?.[1]?.trim();
    if (release) {
      return release;
    }

    const latest = metadata.match(/<latest>([^<]+)<\/latest>/)?.[1]?.trim();
    if (latest) {
      return latest;
    }

    const versions = [...metadata.matchAll(/<version>([^<]+)<\/version>/g)]
      .map((match) => match[1]?.trim())
      .filter((value): value is string => Boolean(value));

    return versions.at(-1) ?? null;
  }

  private static async resolveRepoFromPom(
    groupId: string,
    artifactId: string,
    version: string,
    depth: number,
  ): Promise<RepoKey | null> {
    if (depth > 6) {
      return null;
    }

    const pomFile = this.localPomFile(groupId, artifactId, version);
    if (!(await this.pathExists(pomFile))) {
      return null;
    }

    const content = await this.readText(pomFile);
    if (!content) {
      return null;
    }

    const direct = this.extractGithubRepoKey(content);
    if (direct) {
      return direct;
    }

    const parent = this.extractParentCoords(content);
    if (!parent || this.hasTemplateVar(parent.groupId, parent.artifactId, parent.version)) {
      return null;
    }

    return this.resolveRepoFromPom(parent.groupId, parent.artifactId, parent.version, depth + 1);
  }

  private static extractGithubRepoKey(text: string): RepoKey | null {
    const scmBlock = text.match(/<scm>[\s\S]*?<\/scm>/)?.[0];
    if (scmBlock) {
      const fromScm = this.extractGithubRepoKeyFromText(scmBlock);
      if (fromScm) {
        return fromScm;
      }
    }

    const url = text.match(/<url>([^<]+)<\/url>/)?.[1]?.trim();
    if (url) {
      const fromUrl = this.extractGithubRepoKeyFromText(url);
      if (fromUrl) {
        return fromUrl;
      }
    }

    return this.extractGithubRepoKeyFromText(text);
  }

  private static extractGithubRepoKeyFromText(text: string): RepoKey | null {
    const match = text.match(/github\.com[:/]+([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)/);
    if (!match) {
      return null;
    }

    const owner = match[1];
    const repo = match[2]?.replace(/\.git$/, "");
    if (!owner || !repo) {
      return null;
    }

    return { owner, repo };
  }

  private static extractParentCoords(text: string): { groupId: string; artifactId: string; version: string } | null {
    const parentBlock = text.match(/<parent>[\s\S]*?<\/parent>/)?.[0];
    if (!parentBlock) {
      return null;
    }

    const groupId = parentBlock.match(/<groupId>([^<]+)<\/groupId>/)?.[1]?.trim();
    const artifactId = parentBlock.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1]?.trim();
    const version = parentBlock.match(/<version>([^<]+)<\/version>/)?.[1]?.trim();

    if (!groupId || !artifactId || !version) {
      return null;
    }

    return { groupId, artifactId, version };
  }

  private static localPomFile(groupId: string, artifactId: string, version: string): string {
    return path.join(
      this.localRepoBase(),
      groupId.replace(/\./g, path.sep),
      artifactId,
      version,
      `${artifactId}-${version}.pom`,
    );
  }

  private static async localMetadataFile(groupId: string, artifactId: string): Promise<string | null> {
    const artifactDir = path.join(this.localRepoBase(), groupId.replace(/\./g, path.sep), artifactId);
    const localMetadata = path.join(artifactDir, "maven-metadata-local.xml");
    if (await this.pathExists(localMetadata)) {
      return localMetadata;
    }

    const metadata = path.join(artifactDir, "maven-metadata.xml");
    return (await this.pathExists(metadata)) ? metadata : null;
  }

  private static localRepoBase(): string {
    return path.join(os.homedir(), ".m2", "repository");
  }

  private static gradleCacheBase(): string {
    return path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1");
  }

  private static async findPomInGradleCacheDir(
    base: string,
    groupId: string,
    artifactId: string,
    version: string,
  ): Promise<string | null> {
    const versionDir = path.join(base, groupId, artifactId, version);
    try {
      const entries = await fs.readdir(versionDir, { withFileTypes: true });
      for (const entry of entries) {
        if (!entry.isDirectory()) {
          continue;
        }

        const pomFile = path.join(versionDir, entry.name, `${artifactId}-${version}.pom`);
        if (await this.pathExists(pomFile)) {
          return pomFile;
        }
      }
    } catch {
      return null;
    }

    return null;
  }

  private static async readText(filePath: string): Promise<string | null> {
    try {
      return await fs.readFile(filePath, "utf8");
    } catch {
      return null;
    }
  }

  private static async pathExists(filePath: string): Promise<boolean> {
    try {
      await fs.access(filePath);
      return true;
    } catch {
      return false;
    }
  }

  private static hasTemplateVar(...values: string[]): boolean {
    return values.some((value) => value.includes("$"));
  }
}
