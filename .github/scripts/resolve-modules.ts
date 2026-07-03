/**
 * Resolves which Maven modules need to build based on paths-filter outputs.
 * Writes GitHub step output: list (comma-separated module names).
 */

import * as fs from 'fs';

const outputPath = process.env.GITHUB_OUTPUT;
if (!outputPath) {
  throw new Error('GITHUB_OUTPUT is not defined. This script must run in GitHub Actions.');
}

// Read filter results injected via env vars
const filterContracts = process.env.FILTER_CONTRACTS === 'true';
const filterAuth      = process.env.FILTER_AUTHORIZATION_SERVER === 'true';
const filterGeo       = process.env.FILTER_GEO_SCORING === 'true';
const filterFraud     = process.env.FILTER_FRAUD_DETECTION === 'true';
const filterDecision  = process.env.FILTER_DECISION_ENGINE === 'true';
const filterGateway   = process.env.FILTER_GATEWAY === 'true';

const eventName  = process.env.GITHUB_EVENT_NAME ?? 'push';
const allModules = ['contracts', 'authorization-server', 'geo-scoring', 'fraud-detection', 'decision-engine', 'gateway'] as const;

let changed: string[];

if (eventName === 'workflow_dispatch' || filterContracts) {
  changed = [...allModules];
} else {
  changed  = [];
  if (filterAuth)     changed.push('authorization-server');
  if (filterGeo)      changed.push('geo-scoring');
  if (filterFraud)    changed.push('fraud-detection');
  if (filterDecision) changed.push('decision-engine');
  if (filterGateway)  changed.push('gateway');
}

const list = changed.join(',');

fs.appendFileSync(outputPath, `list=${list}\n`);

console.log(`Event: ${eventName}`);
console.log(`Modules: ${list}`);
