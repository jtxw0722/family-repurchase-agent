#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const manifest = JSON.parse(readFileSync(path.join(root, 'openclaw.plugin.json'), 'utf8'));
const packageJson = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
const source = readFileSync(path.join(root, 'src', 'index.ts'), 'utf8');

const expectedTools = [
  'family_repurchase_import_file',
  'family_repurchase_compare_price',
  'family_repurchase_generate_report'
];

assert.equal(manifest.id, 'family-repurchase-agent');
assert.equal(manifest.version, packageJson.version);
assert.deepEqual(manifest.contracts.tools, expectedTools);
assert.ok(packageJson.openclaw.extensions.includes('./dist/index.js'));
assert.equal(packageJson.peerDependencies.openclaw, '>=2026.3.24-beta.2');
assert.equal(packageJson.openclaw.compat.pluginApi, '>=2026.3.24-beta.2');
assert.equal(packageJson.openclaw.compat.minGatewayVersion, '>=2026.3.24-beta.2');
assert.equal(manifest.configSchema.additionalProperties, false);
assert.ok(manifest.configSchema.properties.apiBaseUrl);
assert.ok(manifest.configSchema.properties.projectRoot);
assert.ok(manifest.configSchema.properties.importAllowedDirs);

for (const toolName of expectedTools) {
  assert.ok(source.includes(toolName), `${toolName} is not declared in src/index.ts`);
}

assert.ok(source.includes('realpathSync'), 'import_file should resolve real paths to avoid symlink escape');
assert.ok(source.includes('definePluginEntry'));
assert.ok(source.includes('api.registerTool'));
assert.ok(source.includes('structuredContent: data'));
assert.ok(source.includes('/api/tools/import-file'));
assert.ok(source.includes('/api/tools/compare-price'));
assert.ok(source.includes('/api/tools/generate-report'));
assert.ok(source.includes('FAMILY_AGENT_API_BASE_URL'));
assert.ok(source.includes('FAMILY_AGENT_IMPORT_ALLOWED_DIRS'));

console.log('OpenClaw plugin smoke test passed.');
