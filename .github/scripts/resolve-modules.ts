/**
 * Resolves which Maven modules need to build based on paths-filter outputs.
 * Writes GitHub step outputs: list, modules (JSON array), build-all.
 */

import * as fs from 'fs';

const outputPath = process.env.GITHUB_OUTPUT;
if (!outputPath) {
  throw new Error('GITHUB_OUTPUT is not defined. This script must run in GitHub Actions.');
}

// Read filter results injected via env vars
const filterContracts = process.env.FILTER_CONTRACTS === 'true';
const filterGeo       = process.env.FILTER_GEO_SCORING === 'true';
const filterFraud     = process.env.FILTER_FRAUD_DETECTION === 'true';
const filterDecision  = process.env.FILTER_DECISION_ENGINE === 'true';

const eventName  = process.env.GITHUB_EVENT_NAME ?? 'push';
const allModules = ['contracts', 'geo-scoring', 'fraud-detection', 'decision-engine'] as const;

let changed: string[];
let buildAll: boolean;

if (eventName === 'workflow_dispatch' || filterContracts) {
  changed  = [...allModules];
  buildAll = true;
} else {
  changed  = [];
  if (filterGeo)      changed.push('geo-scoring');
  if (filterFraud)    changed.push('fraud-detection');
  if (filterDecision) changed.push('decision-engine');
  buildAll = false;
}

const list        = changed.join(',');
const modulesJson = JSON.stringify(changed);

// GitHub Actions expects key=value lines appended to GITHUB_OUTPUT
const lines = [
  `list=${list}`,
  `modules=${modulesJson}`,
  `build-all=${buildAll}`,
];

fs.appendFileSync(outputPath, lines.join('\n') + '\n');

console.log(`Event: ${eventName}`);
console.log(`Build all: ${buildAll}`);
console.log(`Modules: ${list}`);
