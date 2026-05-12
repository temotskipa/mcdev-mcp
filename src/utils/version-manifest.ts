import axios from 'axios';
import { MOJANG_VERSION_MANIFEST_URL, FABRIC_MAVEN_URL } from './config.js';
import { MojangVersionManifest, MojangVersionDetail, MojangDownload } from './types.js';

export type { MojangDownload };

const NETWORK_TIMEOUT_MS = 30000;

function describeAxiosError(e: unknown): string {
  if (axios.isAxiosError(e)) {
    if (e.response) return `HTTP ${e.response.status} ${e.response.statusText}`;
    if (e.code) return `${e.code}${e.message ? `: ${e.message}` : ''}`;
    return e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

// Process-lifetime cache. The Mojang manifest is large-ish and rarely changes
// within a single CLI invocation; before this cache was here, fetchVersionDetail
// and getUnobfuscatedJarDownload each re-fetched it on every call.
let manifestPromise: Promise<MojangVersionManifest> | null = null;

export async function fetchVersionManifest(): Promise<MojangVersionManifest> {
  if (manifestPromise) return manifestPromise;
  manifestPromise = (async () => {
    try {
      const response = await axios.get<MojangVersionManifest>(MOJANG_VERSION_MANIFEST_URL, { timeout: NETWORK_TIMEOUT_MS });
      return response.data;
    } catch (e) {
      // Allow retry on next call rather than poisoning the cache with a failure.
      manifestPromise = null;
      throw new Error(`Failed to fetch Mojang version manifest (${MOJANG_VERSION_MANIFEST_URL}): ${describeAxiosError(e)}`);
    }
  })();
  return manifestPromise;
}

/** For tests / long-lived processes that need to force a manifest refresh. */
export function clearVersionManifestCache(): void {
  manifestPromise = null;
}

export async function fetchVersionDetail(versionId: string): Promise<MojangVersionDetail> {
  const manifest = await fetchVersionManifest();
  const version = manifest.versions.find(v => v.id === versionId);

  if (!version) {
    throw new Error(`Version ${versionId} not found in Mojang manifest`);
  }

  try {
    const response = await axios.get<MojangVersionDetail>(version.url, { timeout: NETWORK_TIMEOUT_MS });
    return response.data;
  } catch (e) {
    throw new Error(`Failed to fetch Mojang version detail for ${versionId} (${version.url}): ${describeAxiosError(e)}`);
  }
}

export async function getUnobfuscatedJarDownload(versionId: string): Promise<MojangDownload | null> {
  // Reuses the cached manifest both for the regular detail fetch and for the unobfuscated lookup.
  const [detail, manifest] = await Promise.all([
    fetchVersionDetail(versionId),
    fetchVersionManifest(),
  ]);

  const unobfVersion = `${versionId}_unobfuscated`;
  const unobf = manifest.versions.find(v => v.id === unobfVersion);

  if (unobf) {
    try {
      const unobfDetail = await axios.get<MojangVersionDetail>(unobf.url, { timeout: NETWORK_TIMEOUT_MS });
      if (unobfDetail.data.downloads?.client) {
        return unobfDetail.data.downloads.client;
      }
    } catch (e) {
      // Fall through to the obfuscated client download — the unobfuscated path is best-effort.
      console.error(`[version-manifest] Unobfuscated jar lookup failed for ${versionId}: ${describeAxiosError(e)}`);
    }
  }

  return detail.downloads.client || null;
}

export async function getMinecraftJarDownload(versionId: string): Promise<MojangDownload | null> {
  const detail = await fetchVersionDetail(versionId);
  return detail.downloads.client || null;
}

export async function fetchFabricApiVersions(): Promise<string[]> {
  const url = `${FABRIC_MAVEN_URL}/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`;
  let body: string;
  try {
    const response = await axios.get<string>(url, { responseType: 'text', timeout: NETWORK_TIMEOUT_MS });
    body = response.data;
  } catch (e) {
    throw new Error(`Failed to fetch Fabric API maven metadata (${url}): ${describeAxiosError(e)}`);
  }

  const versions: string[] = [];
  const versionRegex = /<version>([^<]+)<\/version>/g;
  let match;

  while ((match = versionRegex.exec(body)) !== null) {
    versions.push(match[1]);
  }

  return versions;
}

export function findFabricApiVersion(versions: string[], mcVersion: string): string | null {
  const matching = versions.filter(v => v.includes(`+${mcVersion}`));
  if (matching.length === 0) return null;
  
  matching.sort((a, b) => {
    const aParts = a.split('+')[0].split('.').map(Number);
    const bParts = b.split('+')[0].split('.').map(Number);
    for (let i = 0; i < Math.max(aParts.length, bParts.length); i++) {
      const aVal = aParts[i] || 0;
      const bVal = bParts[i] || 0;
      if (aVal !== bVal) return bVal - aVal;
    }
    return 0;
  });
  
  return matching[0];
}

export function getFabricApiSourcesUrl(version: string): string {
  return `${FABRIC_MAVEN_URL}/net/fabricmc/fabric-api/fabric-api/${version}/fabric-api-${version}-sources.jar`;
}
