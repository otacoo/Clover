#!/usr/bin/env node
/**
 * update_script.js
 *
 * Run automatically by npm as the "version" lifecycle hook.
 * - Bumps versionName / versionCode in Clover/app/build.gradle to match the
 *   new npm version.
 * - Prepends a dated "## YYYY-MM-DD – vX.Y.Z" section to CHANGES.md.
 *
 * versionCode format:  XXYYZZ  (no zero-padding of major)
 *   e.g. v3.0.22 → 30022,  v3.1.5 → 30105,  v10.2.3 → 100203
 *
 * The repo .npmrc sets git-tag-version=false so `npm version` only bumps
 * files and never commits or tags; releases are committed separately.
 */

const fs   = require('fs');
const path = require('path');

// ── Version from npm lifecycle env ──────────────────────────────────────────
const rawVersion = process.env.npm_package_version;
if (!rawVersion) {
    console.error('npm_package_version is not set – run this via npm version, not directly.');
    process.exit(1);
}

const [major, minor, patch] = rawVersion.split('.').map(Number);
const versionName = `v${major}.${minor}.${patch}`;
const versionCode = major * 10000 + minor * 100 + patch;

console.log(`Updating to ${versionName} (versionCode ${versionCode})`);

// ── Paths (relative to workspace root, where this script lives) ──────────────
const root        = __dirname;
const buildGradle = path.join(root, 'Clover', 'app', 'build.gradle');
const changesMd   = path.join(root, 'CHANGES.md');
const updateJson  = path.join(root, 'docs', 'update_api.json');

// ── Update build.gradle ──────────────────────────────────────────────────────
{
    let src = fs.readFileSync(buildGradle, 'utf8');

    src = src.replace(
        /versionName\s*=\s*"v\d+\.\d+\.\d+"/,
        `versionName = "${versionName}"`
    );
    src = src.replace(
        /versionCode\s*=\s*\d+/,
        `versionCode = ${versionCode}`
    );

    fs.writeFileSync(buildGradle, src, 'utf8');
    console.log(`  build.gradle → versionName "${versionName}", versionCode ${versionCode}`);
}

// ── Update CHANGES.md ────────────────────────────────────────────────────────
{
    const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD
    const src   = fs.readFileSync(changesMd, 'utf8');

    // Insert the new entry at the very top of the file, above everything else.
    const output = `## ${today} – ${versionName}\n\n- \n\n` + src;

    fs.writeFileSync(changesMd, output, 'utf8');
    console.log(`  CHANGES.md   → prepended entry for ${today} – ${versionName}`);
}

// ── Update docs/update_api.json ──────────────────────────────────────────────
{
    const today = new Date().toISOString().slice(0, 10);
    const apkUrl = `https://github.com/otacoo/Clover/releases/download/${versionName}/Clover-${versionName}.apk`;
    const releaseUrl = `https://github.com/otacoo/Clover/releases/tag/${versionName}`;

    const api = {
        api_version: 1,
        messages: [
            {
                type: 'update',
                code: versionCode,
                date: `${today}T00:00:00`,
                message_html: `<h2>Clover ${versionName} is available</h2>A new version of Clover is available.<br><br>See the <a href="${releaseUrl}">release notes</a> for details.`,
                apk: {
                    stable: { url: apkUrl }
                }
            }
        ],
        check_interval: 432000000
    };

    fs.writeFileSync(updateJson, JSON.stringify(api, null, 4) + '\n', 'utf8');
    console.log(`  update_api.json → code ${versionCode}, url ${apkUrl}`);
}
