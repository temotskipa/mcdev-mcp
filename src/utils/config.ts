// Upstream URLs and the pinned Vineflower version.
//
// Earlier versions of this file also exported a `Config` interface,
// `DEFAULT_CONFIG` (with empty-string `cacheDir`/`indexDir`/`toolsDir`
// defaults that were never sensible), and a `getConfig()` helper. None of
// them were consumed outside `src/utils/paths.ts` and the empty-string
// defaults were a real footgun, so they were removed. The remaining
// exports are the only `./config` symbols anyone actually imports.

export const VINEFLOWER_VERSION = '1.11.2';
export const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;

export const MOJANG_VERSION_MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
export const FABRIC_MAVEN_URL = 'https://maven.fabricmc.net';
