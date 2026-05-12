import * as fs from 'fs';
import * as path from 'path';
import https from 'https';
import { MOJANG_VERSION_MANIFEST_URL } from '../utils/config.js';
import { ensureDir } from '../utils/paths.js';

export type ProgressCallback = (stage: string, progress: number, message: string) => void;

export interface VersionDownload {
  url: string;
  sha1: string;
  size: number;
}

export interface VersionInfo {
  id: string;
  downloads: {
    client: VersionDownload;
    client_mappings?: VersionDownload;
  };
}

export interface VersionManifest {
  latest: {
    release: string;
    snapshot: string;
  };
  versions: Array<{
    id: string;
    type: string;
    url: string;
  }>;
}

const UNOBFUSCATED_JARS: Record<string, string> = {
  '1.21.11': 'https://piston-data.mojang.com/v1/objects/4509ee9b65f226be61142d37bf05f8d28b03417b/client.jar',
};

export function hasUnobfuscatedJar(version: string): boolean {
  return version in UNOBFUSCATED_JARS;
}

const MAX_REDIRECTS = 5;

async function fetchJson<T>(url: string, redirectsLeft: number = MAX_REDIRECTS): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    https.get(url, { timeout: 30000 }, (response) => {
      if (response.statusCode === 301 || response.statusCode === 302 || response.statusCode === 307 || response.statusCode === 308) {
        const redirectUrl = response.headers.location;
        if (redirectsLeft <= 0) {
          reject(new Error(`Too many redirects fetching ${url}`));
          return;
        }
        if (redirectUrl) {
          // Drain the redirect response so the socket can be reused / closed cleanly,
          // then chain the recursive call's result onto our outer Promise.
          response.resume();
          fetchJson<T>(redirectUrl, redirectsLeft - 1).then(resolve, reject);
          return;
        }
        reject(new Error(`HTTP ${response.statusCode} with no Location header: ${url}`));
        return;
      }

      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error(`HTTP ${response.statusCode}: ${url}`));
        return;
      }

      let data = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { data += chunk; });
      response.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(new Error(`Failed to parse JSON from ${url}: ${e instanceof Error ? e.message : String(e)}`));
        }
      });
    }).on('error', reject).on('timeout', function (this: import('http').ClientRequest) {
      this.destroy(new Error(`Request timeout fetching ${url}`));
    });
  });
}

export async function fetchVersionManifest(): Promise<VersionManifest> {
  return fetchJson<VersionManifest>(MOJANG_VERSION_MANIFEST_URL);
}

export async function fetchVersionInfo(version: string): Promise<VersionInfo> {
  const manifest = await fetchVersionManifest();
  const versionEntry = manifest.versions.find(v => v.id === version);
  
  if (!versionEntry) {
    throw new Error(`Version ${version} not found in manifest`);
  }
  
  return fetchJson<VersionInfo>(versionEntry.url);
}

export async function downloadFile(
  url: string,
  dest: string,
  progressCb?: ProgressCallback,
  stage: string = 'download'
): Promise<void> {
  ensureDir(path.dirname(dest));

  return new Promise<void>((resolve, reject) => {
    const cleanupDest = () => {
      try { if (fs.existsSync(dest)) fs.unlinkSync(dest); } catch {}
    };

    const request = (currentUrl: string, redirectsLeft: number) => {
      // Create a fresh write stream for each request. The previous design
      // hoisted this outside the closure and reused it across redirects, which
      // hung the outer Promise: after `file.close()` on the redirect, the
      // recursive call piped into the closed stream and `file.on('finish')`
      // never fired. See .dream/review.md HIGH "Async/Promise correctness".
      const file = fs.createWriteStream(dest);
      let downloaded = 0;
      let total = 0;
      let settled = false;
      const settleReject = (err: Error) => {
        if (settled) return;
        settled = true;
        try { file.close(); } catch {}
        cleanupDest();
        reject(err);
      };
      const settleResolve = () => {
        if (settled) return;
        settled = true;
        resolve();
      };

      const req = https.get(currentUrl, { timeout: 60000 }, (response) => {
        if (response.statusCode === 301 || response.statusCode === 302 || response.statusCode === 307 || response.statusCode === 308) {
          const redirectUrl = response.headers.location;
          response.resume();          // drain the redirect response
          try { file.close(); } catch {}
          cleanupDest();
          if (redirectsLeft <= 0) {
            settleReject(new Error(`Too many redirects downloading ${url}`));
            return;
          }
          if (!redirectUrl) {
            settleReject(new Error(`HTTP ${response.statusCode} with no Location header: ${currentUrl}`));
            return;
          }
          request(redirectUrl, redirectsLeft - 1);
          return;
        }

        if (response.statusCode !== 200) {
          response.resume();
          settleReject(new Error(`HTTP ${response.statusCode}: ${currentUrl}`));
          return;
        }

        total = parseInt(response.headers['content-length'] || '0', 10);
        downloaded = 0;

        let lastPercent = 0;
        response.on('data', (chunk) => {
          downloaded += chunk.length;
          if (total > 0 && progressCb) {
            const percent = Math.round((downloaded / total) * 100);
            if (percent >= lastPercent + 5 || percent === 100) {
              lastPercent = percent;
              const mb = (downloaded / 1024 / 1024).toFixed(1);
              const totalMb = (total / 1024 / 1024).toFixed(1);
              progressCb(stage, percent, `Downloading... ${mb}/${totalMb} MB`);
            }
          }
        });

        response.on('error', settleReject);
        file.on('error', settleReject);

        response.pipe(file);

        file.on('finish', () => {
          file.close((closeErr) => {
            if (closeErr) {
              settleReject(closeErr);
              return;
            }
            settleResolve();
          });
        });
      });

      req.on('error', settleReject);
      req.on('timeout', () => {
        req.destroy(new Error(`Download timeout fetching ${currentUrl}`));
      });
    };

    request(url, MAX_REDIRECTS);
  });
}

export async function downloadClientJar(
  version: string,
  dest: string,
  progressCb?: ProgressCallback
): Promise<void> {
  if (progressCb) {
    progressCb('download', 0, 'Downloading client JAR...');
  }
  
  const unobfuscatedUrl = UNOBFUSCATED_JARS[version];
  if (unobfuscatedUrl) {
    await downloadFile(unobfuscatedUrl, dest, progressCb, 'download');
    if (progressCb) {
      progressCb('download', 100, 'Unobfuscated JAR downloaded.');
    }
    return;
  }
  
  const versionInfo = await fetchVersionInfo(version);
  await downloadFile(versionInfo.downloads.client.url, dest, progressCb, 'download');
  
  if (progressCb) {
    progressCb('download', 100, 'Client JAR downloaded.');
  }
}

export async function downloadMappings(
  versionInfo: VersionInfo,
  destDir: string,
  progressCb?: ProgressCallback
): Promise<boolean> {
  if (!versionInfo.downloads.client_mappings) {
    if (progressCb) {
      progressCb('download', 100, 'No mappings available (dev snapshot).');
    }
    return false;
  }
  
  const dest = path.join(destDir, 'client.txt');
  
  if (progressCb) {
    progressCb('download', 0, 'Downloading ProGuard mappings...');
  }
  
  await downloadFile(versionInfo.downloads.client_mappings.url, dest, progressCb, 'download');
  
  if (progressCb) {
    progressCb('download', 100, 'Mappings downloaded.');
  }
  
  return true;
}
