// Share browser discovery with the existing test and screenshot harnesses.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findChrome } from './lib/harness.mjs';

const PACKAGE = '@playwright/mcp@0.0.82';
const FLAGS = ['--headless', '--isolated'];

export function playwrightMcpCommand(browser, platform = process.platform, outputDir = null) {
  if (!browser) throw new Error('Playwright MCP needs Chrome or Edge; set CHROME_PATH');

  // A browser path with spaces breaks cmd /c argument parsing on Windows.
  const env = { PLAYWRIGHT_MCP_EXECUTABLE_PATH: browser };
  if (outputDir) env.PLAYWRIGHT_MCP_OUTPUT_DIR = outputDir;
  const args = ['--yes', PACKAGE, ...FLAGS];
  if (platform !== 'win32') return { command: 'npx', args, env };

  return {
    command: process.env.ComSpec || 'cmd.exe',
    args: ['/d', '/s', '/c', `npx ${args.join(' ')}`],
    env,
  };
}

function main() {
  const browser = findChrome();
  if (!browser || !existsSync(browser)) {
    console.error('Playwright MCP needs Chrome or Edge; set CHROME_PATH to its executable.');
    process.exitCode = 1;
    return;
  }

  const outputDir = mkdtempSync(join(tmpdir(), 'ideaforge-playwright-mcp-'));
  const { command, args, env } = playwrightMcpCommand(browser, process.platform, outputDir);
  const child = spawn(command, args, {
    stdio: 'inherit', windowsHide: true, env: { ...process.env, ...env },
  });
  child.on('error', (err) => {
    console.error(`Playwright MCP could not start: ${err.message}`);
    process.exitCode = 1;
  });
  child.on('exit', (code) => { process.exitCode = code ?? 1; });
  child.on('close', () => {
    try {
      rmSync(outputDir, { recursive: true, force: true });
    } catch (err) {
      console.error(`Playwright MCP could not remove its temporary output: ${err.message}`);
      process.exitCode = 1;
    }
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
