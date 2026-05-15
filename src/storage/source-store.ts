import * as fs from 'fs';
import * as path from 'path';
import {
  getMinecraftSourceDir,
  getFabricApiCacheDir,
  getVersionedIndexManifestPath,
  getVersionedPackageIndexPath,
  isVersionIndexed
} from '../utils/paths.js';
import { readJsonFileOrNull } from '../utils/json-file.js';
import { getParserBackend } from '../indexer/parser.js';
import { SearchResult, PackageIndex, ClassInfo, MethodInfo, IndexManifest } from '../utils/types.js';

export class SourceStore {
  private version: string | null = null;
  private manifest: IndexManifest | null = null;
  private packageCache: Map<string, PackageIndex> = new Map();

  setVersion(version: string): void {
    if (this.version !== version) {
      this.version = version;
      this.manifest = null;
      this.packageCache.clear();
    }
  }

  getVersion(): string | null {
    return this.version;
  }

  isReady(): boolean {
    if (!this.version) return false;
    return isVersionIndexed(this.version);
  }
  
  private getManifest(): IndexManifest | null {
    if (!this.version) return null;

    if (!this.manifest) {
      const manifestPath = getVersionedIndexManifestPath(this.version);
      // readJsonFileOrNull logs corruption to stderr with the path + parse
      // error; missing-file is silent. Either way the legacy null return
      // shape is preserved so callers don't change.
      this.manifest = readJsonFileOrNull<IndexManifest>(manifestPath, `source-store/manifest:${this.version}`);
      if (this.manifest) this.warnIfStaleIndexer(this.manifest);
    }
    return this.manifest;
  }

  // One-shot per version load. Tells a user who flipped `MCDEV_AST_PARSER=1`
  // but never re-ran init that they're still reading the regex-parser-era
  // index (which works, just doesn't reflect the new parser's better field /
  // method extraction). We log instead of refusing because the data is still
  // usable; suppressed via MCDEV_SUPPRESS_INDEXER_HINT=1.
  private indexerHintShown: Set<string> = new Set();
  private warnIfStaleIndexer(manifest: IndexManifest): void {
    if (process.env.MCDEV_SUPPRESS_INDEXER_HINT === '1') return;
    if (!this.version || this.indexerHintShown.has(this.version)) return;
    const wrote = manifest.indexerVersion ?? 'regex';
    const running = getParserBackend();
    if (wrote !== running) {
      this.indexerHintShown.add(this.version);
      console.error(
        `[source-store/manifest:${this.version}] Index was built with the '${wrote}' parser, ` +
        `but the server is now running the '${running}' parser.\n` +
        `  This is fine — existing indices still work — but the new parser ` +
        `would produce a better index. Run \`mcdev-mcp rebuild -v ${this.version}\` ` +
        `(or \`init -v ${this.version}\` for a full re-fetch) to refresh.\n` +
        `  Set MCDEV_SUPPRESS_INDEXER_HINT=1 to silence this message.`
      );
    }
  }
  
  getMinecraftVersion(): string | null {
    return this.getManifest()?.minecraftVersion || null;
  }
  
  getFabricApiVersion(): string | null {
    return this.getManifest()?.fabricApiVersion || null;
  }
  
  /**
   * List classes under a package. If `limit` is supplied, the search early-exits
   * once that many matches have been collected and the second component of the
   * tuple is `true` to indicate the caller should treat the count as a lower
   * bound. With no limit, every match is returned and `truncated` is `false`.
   */
  listClasses(
    packagePath: string,
    limit?: number,
  ): { results: { className: string; simpleName: string; sourcePath: string }[]; truncated: boolean } {
    const manifest = this.getManifest();
    if (!manifest) return { results: [], truncated: false };

    const results: { className: string; simpleName: string; sourcePath: string }[] = [];
    const packageLower = packagePath.toLowerCase();
    const cap = limit && limit > 0 ? limit : Infinity;

    const namespaces: Array<'minecraft' | 'fabric'> = ['minecraft', 'fabric'];

    for (const namespace of namespaces) {
      const packages = namespace === 'minecraft' ? manifest.packages.minecraft : manifest.packages.fabric;

      for (const packageName of packages) {
        if (packageName.toLowerCase() === packageLower || packageName.toLowerCase().startsWith(packageLower + '.')) {
          const pkgIndex = this.getPackage(namespace, packageName);
          if (!pkgIndex) continue;

          for (const [simpleName, classInfo] of Object.entries(pkgIndex.classes)) {
            results.push({
              className: `${packageName}.${simpleName}`,
              simpleName,
              sourcePath: this.resolveSourcePath(classInfo.sourcePath, namespace),
            });
            if (results.length >= cap) {
              return { results, truncated: true };
            }
          }
        }
      }
    }

    return { results, truncated: false };
  }
  
  listPackages(namespace?: 'minecraft' | 'fabric'): string[] {
    const manifest = this.getManifest();
    if (!manifest) return [];
    
    if (namespace) {
      return namespace === 'minecraft' ? manifest.packages.minecraft : manifest.packages.fabric;
    }
    
    return [...manifest.packages.minecraft, ...manifest.packages.fabric];
  }
  
  /**
   * Find subclasses or implementors of `className`. Optional `limit` enables
   * early-exit once enough matches have been collected; the second component
   * of the return tuple is `true` when the search exited early.
   */
  findHierarchy(
    className: string,
    direction: 'subclasses' | 'implementors',
    limit?: number,
  ): { results: { className: string; sourcePath: string }[]; truncated: boolean } {
    const manifest = this.getManifest();
    if (!manifest) return { results: [], truncated: false };

    const results: { className: string; sourcePath: string }[] = [];
    const cap = limit && limit > 0 ? limit : Infinity;

    const namespaces: Array<'minecraft' | 'fabric'> = ['minecraft', 'fabric'];

    for (const namespace of namespaces) {
      const packages = namespace === 'minecraft' ? manifest.packages.minecraft : manifest.packages.fabric;

      for (const packageName of packages) {
        const pkgIndex = this.getPackage(namespace, packageName);
        if (!pkgIndex) continue;

        for (const [simpleName, classInfo] of Object.entries(pkgIndex.classes)) {
          const fullName = `${packageName}.${simpleName}`;
          let matched = false;

          if (direction === 'subclasses') {
            if (classInfo.super === className) matched = true;
          } else if (direction === 'implementors') {
            if (classInfo.interfaces && classInfo.interfaces.includes(className)) matched = true;
          }

          if (matched) {
            results.push({
              className: fullName,
              sourcePath: this.resolveSourcePath(classInfo.sourcePath, namespace),
            });
            if (results.length >= cap) {
              return { results, truncated: true };
            }
          }
        }
      }
    }

    return { results, truncated: false };
  }

  /**
   * Search across the index for class/method/field hits. `limit` defaults to
   * 50 when unset (preserves the previous hard-coded slice). Early-exits as
   * soon as `limit` results have been collected — this avoids reading every
   * package index from disk just to slice the first 50 off the end, which
   * was the original behaviour.
   */
  search(
    query: string,
    type?: 'class' | 'method' | 'field',
    limit?: number,
  ): { results: SearchResult[]; truncated: boolean } {
    const manifest = this.getManifest();
    if (!manifest) return { results: [], truncated: false };

    const results: SearchResult[] = [];
    const queryLower = query.toLowerCase();
    const cap = limit && limit > 0 ? limit : 50;

    const scan = (namespace: 'minecraft' | 'fabric'): boolean => {
      const packages = namespace === 'minecraft' ? manifest.packages.minecraft : manifest.packages.fabric;
      for (const packageName of packages) {
        const pkgIndex = this.getPackage(namespace, packageName);
        if (!pkgIndex) continue;
        const filled = this.searchInPackage(pkgIndex, packageName, queryLower, type, namespace, results, cap);
        if (filled) return true;
      }
      return false;
    };

    if (scan('minecraft')) return { results, truncated: true };
    if (scan('fabric'))    return { results, truncated: true };

    return { results, truncated: false };
  }

  private searchInPackage(
    pkgIndex: PackageIndex,
    packageName: string,
    queryLower: string,
    type: 'class' | 'method' | 'field' | undefined,
    namespace: 'minecraft' | 'fabric',
    results: SearchResult[],
    cap: number,
  ): boolean {
    for (const [className, classInfo] of Object.entries(pkgIndex.classes)) {
      const fullName = `${packageName}.${className}`;

      if (!type || type === 'class') {
        if (className.toLowerCase().includes(queryLower)) {
          results.push({
            type: 'class',
            className: fullName,
            name: className,
            sourcePath: this.resolveSourcePath(classInfo.sourcePath, namespace),
            kind: classInfo.kind,
            superClass: classInfo.super,
            interfaces: classInfo.interfaces,
            fieldCount: classInfo.fields.length,
            methodCount: classInfo.methods.length,
          });
          if (results.length >= cap) return true;
        }
      }

      if (!type || type === 'field') {
        for (const field of classInfo.fields) {
          if (field.name.toLowerCase().includes(queryLower)) {
            results.push({
              type: 'field',
              className: fullName,
              name: field.name,
              signature: `${field.type} ${field.name}`,
              sourcePath: this.resolveSourcePath(classInfo.sourcePath, namespace),
              modifiers: field.modifiers,
            });
            if (results.length >= cap) return true;
          }
        }
      }

      if (!type || type === 'method') {
        for (const method of classInfo.methods) {
          if (method.name.toLowerCase().includes(queryLower)) {
            results.push({
              type: 'method',
              className: fullName,
              name: method.name,
              signature: this.formatMethodSignature(method),
              sourcePath: this.resolveSourcePath(classInfo.sourcePath, namespace),
              lineStart: method.lineStart,
              modifiers: method.modifiers,
            });
            if (results.length >= cap) return true;
          }
        }
      }
    }
    return false;
  }
  
  getClass(className: string): { info: ClassInfo; source: string; sourcePath: string } | null {
    const manifest = this.getManifest();
    if (!manifest) return null;
    
    const { packageName, simpleClassName, namespace } = this.parseClassName(className);
    
    const pkgIndex = this.getPackage(namespace, packageName);
    if (!pkgIndex) return null;
    
    const classInfo = pkgIndex.classes[simpleClassName];
    if (!classInfo) return null;
    
    const sourcePath = this.resolveSourcePath(classInfo.sourcePath, namespace);
    const source = this.readSource(sourcePath);
    
    if (!source) return null;
    
    return { info: classInfo, source, sourcePath };
  }
  
  getMethod(
    className: string,
    methodName: string,
    _signatureHint?: string
  ): { method: MethodInfo; source: string; classInfo: ClassInfo; sourcePath: string } | null {
    const classResult = this.getClass(className);
    if (!classResult) return null;
    
    const { info, source, sourcePath } = classResult;
    
    let method = info.methods.find(m => m.name === methodName);
    
    if (!method) {
      method = info.methods.find(m => 
        m.name.toLowerCase() === methodName.toLowerCase()
      );
    }
    
    if (!method) return null;
    
    const methodSource = this.extractMethodSource(source, method);
    
    return { 
      method, 
      source: methodSource, 
      classInfo: info, 
      sourcePath 
    };
  }
  
  private parseClassName(fullName: string): { packageName: string; simpleClassName: string; namespace: 'minecraft' | 'fabric' } {
    const parts = fullName.split('.');
    
    if (fullName.startsWith('net.fabricmc')) {
      const className = parts.pop() || '';
      return {
        packageName: parts.join('.'),
        simpleClassName: className,
        namespace: 'fabric',
      };
    }
    
    const className = parts.pop() || '';
    return {
      packageName: parts.join('.'),
      simpleClassName: className,
      namespace: 'minecraft',
    };
  }
  
  private getPackage(namespace: 'minecraft' | 'fabric', packageName: string): PackageIndex | null {
    if (!this.version) return null;

    const cacheKey = `${namespace}:${packageName}`;

    if (!this.packageCache.has(cacheKey)) {
      const indexPath = getVersionedPackageIndexPath(namespace, packageName, this.version);
      const pkg = readJsonFileOrNull<PackageIndex>(
        indexPath,
        `source-store/package:${this.version}/${namespace}/${packageName}`
      );
      if (pkg) this.packageCache.set(cacheKey, pkg);
    }

    return this.packageCache.get(cacheKey) || null;
  }
  
  private resolveSourcePath(storedPath: string, namespace: 'minecraft' | 'fabric'): string {
    if (path.isAbsolute(storedPath)) {
      return storedPath;
    }
    
    if (!this.version) return storedPath;
    
    const manifest = this.getManifest();
    if (!manifest) return storedPath;
    
    if (namespace === 'fabric' && manifest.fabricApiVersion) {
      return path.join(getFabricApiCacheDir(manifest.fabricApiVersion), storedPath);
    }
    
    return path.join(getMinecraftSourceDir(this.version), storedPath);
  }
  
  private readSource(sourcePath: string): string | null {
    if (!fs.existsSync(sourcePath)) return null;
    return fs.readFileSync(sourcePath, 'utf-8');
  }
  
  private formatMethodSignature(method: MethodInfo): string {
    const params = method.params.map(p => `${p.type} ${p.name}`).join(', ');
    return `${method.returnType} ${method.name}(${params})`;
  }
  
  private extractMethodSource(source: string, method: MethodInfo): string {
    const lines = source.split('\n');
    const startLine = Math.max(0, method.lineStart - 3);
    const endLine = Math.min(lines.length, method.lineEnd + 3);
    
    return lines.slice(startLine, endLine).join('\n');
  }
}

export const sourceStore = new SourceStore();
